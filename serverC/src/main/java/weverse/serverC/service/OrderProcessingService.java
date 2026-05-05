package weverse.serverC.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.dto.ServerCResponse;
import weverse.serverC.repository.FinalOrderRepository;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderProcessingService {

    private final FinalOrderRepository finalOrderRepository;
    private final PgClient pgClient;

    private final TransactionTemplate transactionTemplate;

    public ServerCResponse processBulkOrders(List<PurchaseMessage> messages) {
        log.info("[OrderProcessingService] 벌크 주문 비즈니스 로직 시작: 총 건수 = {}", messages.size());

        // IN절 파라미터 제한을 피하기 위해 청크(Chunk) 분할 처리
        List<List<PurchaseMessage>> chunks = partition(messages, 500);
        List<String> totalFailedTraceIds = new ArrayList<>();

        for (List<PurchaseMessage> chunk : chunks) {

            // Redis 없이 RDBMS의 Unique 제약조건을 활용한 이중 결제 방어벽
            List<PurchaseMessage> claimedOrders = finalOrderRepository.claimOrders(chunk);

            if (claimedOrders.isEmpty()) {
                log.info("[OrderProcessingService] 해당 청크의 모든 주문이 중복되어 스킵합니다.");
                continue; // 모두 중복이면 패스
            }

            // PG 결제 요청 (DB 커넥션 없는 상태에서 통신)
            List<String> failedTraceIds = pgClient.processPayments(claimedOrders);
            totalFailedTraceIds.addAll(failedTraceIds);

            List<String> successTraceIds = claimedOrders.stream()
                                                        .map(PurchaseMessage::traceId)
                                                        .filter(id -> !failedTraceIds.contains(id))
                                                        .toList();

            // DB 뻗음 대비 완벽한 보상 트랜잭션 로직
            try {
                // 이 안에서만 DB 트랜잭션이 열림
                transactionTemplate.executeWithoutResult(status -> {
                    log.info("[OrderProcessingService] DB 상태 업데이트 트랜잭션 시작 (SUCCESS: {}, FAIL: {})",
                            successTraceIds.size(), failedTraceIds.size());
                    finalOrderRepository.updateOrderStatus(successTraceIds, "SUCCESS");
                    finalOrderRepository.updateOrderStatus(failedTraceIds, "FAIL");
                });
            } catch (Exception e) {
                log.error("🚨 DB 상태 업데이트 실패! PG 승인 강제 취소를 진행합니다.", e);
                // DB가 죽어서 SUCCESS 마킹을 못 했으므로, 고객의 돈을 환불해 줌
                pgClient.cancelPayments(successTraceIds);
                // 서버 B에게 이 건들은 모두 실패(FAIL) 처리하라고 응답에 포함시킴
                totalFailedTraceIds.addAll(successTraceIds);
            }
        }

        log.info("[OrderProcessingService] 모든 벌크 주문 처리 완료");
        return new ServerCResponse(true, "Processed", totalFailedTraceIds);
    }

    // List Chunk 유틸리티
    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> parts = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            parts.add(new ArrayList<>(list.subList(i, Math.min(list.size(), i + size))));
        }
        return parts;
    }
}