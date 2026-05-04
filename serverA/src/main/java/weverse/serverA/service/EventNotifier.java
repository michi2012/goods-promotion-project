package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventNotifier {

    private final RestTemplate restTemplate;

    @Value("${external.server-b.url}")
    private String serverBUrl;

    private final ConcurrentMap<Long, Long> recentlyNotified = new ConcurrentHashMap<>();
    private static final long NOTIFY_INTERVAL = 5000L;

    @Async
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
            restTemplate.postForEntity(url, null, String.class);
            log.info("📢 [이벤트 전송] 서버 B에 상품 [{}] 품절 알림 완료 (원자적 방어 성공)", goodsId);

        } catch (Exception e) {
            log.error("🚨 서버 B로 품절 알림 전송 실패 (상품 ID: {})", goodsId, e);
            // 실패 시 다음 스레드가 즉시 쏠 수 있도록 맵에서 제거
            recentlyNotified.remove(goodsId);
        }
    }
}