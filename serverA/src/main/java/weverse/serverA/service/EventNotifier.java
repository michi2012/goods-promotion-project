package weverse.serverA.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import weverse.serverA.client.ExternalApiClient;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventNotifier {

    private final ExternalApiClient externalApiClient;

    @Value("${external.server-b.url}")
    private String serverBUrl;

    private final ConcurrentMap<Long, Long> recentlyNotified = new ConcurrentHashMap<>();
    private static final long NOTIFY_INTERVAL = 5000L;

    @Async
    @EventListener
    public void notifySoldOutToServerB(Long goodsId) {
        long now = System.currentTimeMillis();

        // 💡 발송 여부를 판단할 스위치 배열 (람다 내부에서 값을 변경하기 위해 배열 사용)
        boolean[] canSend = {false};

        // 💡 [핵심] compute는 이 람다식이 끝날 때까지 다른 스레드가 해당 goodsId에 접근하지 못하게 락(Lock)을 겁니다.
        recentlyNotified.compute(goodsId, (key, lastTime) -> {
            if (lastTime == null || (now - lastTime >= NOTIFY_INTERVAL)) {
                canSend[0] = true; // 보낼 자격 획득!
                return now;        // 새로운 시간으로 업데이트
            }
            return lastTime;       // 아직 쿨다운 중이면 기존 시간 유지
        });

        // 보낼 자격을 얻지 못한 나머지 수천 개의 스레드는 여기서 전부 커트당함
        if (!canSend[0]) {
            return;
        }

        try {
            String url = serverBUrl + "/api/v1/b/goods/" + goodsId + "/sold-out";
            externalApiClient.sendSoldOutEvent(url);
        } catch (Exception e) {
            // 서킷 브레이커에 의해 차단된 에러인지 확인
            if (e.getCause() instanceof CallNotPermittedException
                    || e.getMessage().contains("Circuit Breaker OPEN")) {
                log.warn("🛑 [CB OPEN] 서버 B 장애로 인해 알림 발송을 중단하고 쿨다운을 유지합니다.");
                // remove를 하지 않음으로써 NOTIFY_INTERVAL 동안 추가 스레드 진입 차단
            } else {
                log.error("🚨 단순 통신 실패: 즉시 재시도를 위해 맵에서 제거합니다.");
                recentlyNotified.remove(goodsId);
            }
        }
    }
}