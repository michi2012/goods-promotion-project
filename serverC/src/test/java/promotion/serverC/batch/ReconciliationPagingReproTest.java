package promotion.serverC.batch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import promotion.serverC.repository.PgSettlementLineRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * 재현 ③ 처리 중 상태 변경에도 대상 누락 없음 (명세 S-11).
 * D에 승인된 결제 6건을 페이지 2건씩 읽는 동안, 첫 페이지를 처리한 직후 앞쪽 2건이 실시간 환불로 취소된다.
 * "상태 조건 + OFFSET" 구현은 결과 집합이 앞으로 당겨져 2건을 건너뛰고,
 * Job("이벤트 시각 조건 + 키셋")은 대상이 흔들리지 않아 6건을 모두 판정한다.
 */
class ReconciliationPagingReproTest extends ReconciliationJobTestSupport {

    private static final int EVENTS = 6;
    private static final int PAGE = 2;
    private static final LocalDateTime REFUND_AT = D.plusDays(1).atTime(3, 0); // 대사 실행 중 들어온 환불

    @MockitoSpyBean
    private PgSettlementLineRepository lineRepository;

    @BeforeEach
    void seed() {
        reset(lineRepository);
        for (int i = 1; i <= EVENTS; i++) {
            payment("o-" + i, "PAID", 1_000L, D.atTime(10, i), null);
        }
        pgFile(D); // PG에는 없음 → 판정된 승인은 모두 ① 우리만 있음으로 남는다
        pgFile(D.minusDays(1));
    }

    @Test
    @DisplayName("단순 구현(상태 조건 + OFFSET): 첫 페이지 처리 중 앞쪽 2건이 취소되면 다음 페이지에서 2건을 건너뛴다")
    void naive_OFFSET은_누락() {
        List<String> processed = new ArrayList<>();
        for (int offset = 0; ; offset += PAGE) {
            List<String> page = jdbcTemplate.queryForList(
                    "SELECT order_id FROM payments WHERE status = 'PAID' AND approved_at >= ? AND approved_at < ? " +
                    "ORDER BY approved_at, id LIMIT ? OFFSET ?",
                    String.class, D.atStartOfDay(), D.plusDays(1).atStartOfDay(), PAGE, offset);
            if (page.isEmpty()) {
                break;
            }
            processed.addAll(page);
            if (offset == 0) {
                refundFirstTwo();
            }
        }

        assertThat(processed).containsExactly("o-1", "o-2", "o-5", "o-6"); // o-3, o-4 누락
    }

    @Test
    @DisplayName("Job(이벤트 시각 + 키셋): 같은 시점에 취소가 들어와도 D의 승인 6건을 모두 판정한다")
    void job_키셋은_누락없음() throws Exception {
        AtomicBoolean refunded = new AtomicBoolean();
        doAnswer(invocation -> {
            if (refunded.compareAndSet(false, true)) {
                refundFirstTwo(); // 첫 chunk 판정 중 = 첫 페이지를 읽은 뒤
            }
            return invocation.callRealMethod();
        }).when(lineRepository).findAll(any(), anyString(), anyCollection());

        assertThat(run(D).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        assertThat(mismatches(D)).extracting(Mismatch::orderId)
                .containsExactly("o-1", "o-2", "o-3", "o-4", "o-5", "o-6");
    }

    private void refundFirstTwo() {
        cancel("o-1", REFUND_AT);
        cancel("o-2", REFUND_AT);
    }
}
