package weverse.serverA.service.outbox;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import weverse.serverA.client.ExternalApiClient;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.OutboxRepository;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PushScheduler {

    @Value("${external.server-b.url}")
    private String serverBUrl;

    private volatile boolean isBackpressureActive = false;
    private volatile long backpressureEndTime = 0;
    private static final String PURCHASE_BULK_API = "/api/v1/b/purchases/bulk";

    private final ExternalApiClient externalApiClient;
    private final OutboxRepository outboxRepository;
    private final OutboxClaimer outboxClaimer;

    @Scheduled(fixedDelay = 100)
    public void pushToServerB() {
        // 배압(Backpressure) 유지 시간 체크
        if (isBackpressureActive && System.currentTimeMillis() < backpressureEndTime) {
            return;
        }
        isBackpressureActive = false;

        // 짧은 트랜잭션으로 PUBLISHING 변경 후 가져옴
        List<RequestOutbox> publishingList = outboxClaimer.claimSuccessRecords();
        if (publishingList.isEmpty()) return;

        // 빠른 ID 추출 (성공/실패 벌크 업데이트용)
        List<Long> targetIds = publishingList.stream().map(RequestOutbox::getId).toList();

        if (targetIds.isEmpty()) return;

        try {
            List<PurchaseMessage> payloads = publishingList.stream()
                                                           .map(outbox -> new PurchaseMessage(
                                                                   outbox.getTraceId(),
                                                                   outbox.getUserId(),
                                                                   outbox.getGoodsId(),
                                                                   outbox.getQuantity(),
                                                                   outbox.getPaymentMethod(),
                                                                   outbox.getShippingAddress(),
                                                                   outbox.getZipCode(),
                                                                   outbox.getPhoneNumber(),
                                                                   outbox.getEmail(),
                                                                   outbox.getDeliveryMemo(),
                                                                   outbox.getClientIp()
                                                           )).collect(Collectors.toList());

            String url = serverBUrl + PURCHASE_BULK_API;
            ResponseEntity<String> response = externalApiClient.pushBulkToServerB(url, payloads);

            if (response.getStatusCode().is2xxSuccessful()) {
                outboxRepository.updateStatusByIds(OutboxStatus.SENT, targetIds);
            }

        } catch (CallNotPermittedException e) {
            log.error("🛑 [Circuit Open] 서버 B가 차단됨. 서킷 유지시간 동안 배압 모드 진입.");
            applyBackpressureAndRollback("Circuit Breaker Open", targetIds, 10000);
        } catch (HttpClientErrorException.TooManyRequests e) {
            // Server B가 429를 던질 때 (Resilience4j와 별개로 작동하는 배압)
            applyBackpressureAndRollback("Server B 429 수신", targetIds, 3000);
        } catch (Exception e) {
            // 타임아웃 등 기타 장애
            applyBackpressureAndRollback("Server B 기타 장애", targetIds, 3000);
        }
    }

     // 배압을 활성화하고 PUBLISHING 상태의 데이터를 다시 SUCCESS로 롤백시키는 공통 메서드
    private void applyBackpressureAndRollback(String reason, List<Long> targetIds, long duration) {
        this.isBackpressureActive = true;
        this.backpressureEndTime = System.currentTimeMillis() + duration;
        outboxRepository.updateStatusByIds(OutboxStatus.SUCCESS, targetIds);
    }

}