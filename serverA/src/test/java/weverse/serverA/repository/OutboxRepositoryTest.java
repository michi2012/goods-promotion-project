package weverse.serverA.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxRepositoryTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private TestEntityManager testEntityManager;

    @Test
    @DisplayName("특정 유저가 주어진 상태의 주문을 가지고 있는지 확인한다.")
    void existsByUserIdAndStatusIn() {
        // Given
        Long userId = 100L;
        outboxRepository.save(createOutbox(userId, OutboxStatus.PENDING));

        // When
        boolean exists = outboxRepository.existsByUserIdAndStatusIn(
                userId, List.of(OutboxStatus.PENDING, OutboxStatus.SUCCESS)
        );
        boolean notExists = outboxRepository.existsByUserIdAndStatusIn(
                userId, List.of(OutboxStatus.FAIL)
        );

        // Then
        assertThat(exists).isTrue();
        assertThat(notExists).isFalse();
    }

    @Test
    @DisplayName("여러 ID를 받아 한 번의 쿼리로 상태를 벌크 업데이트한다.")
    void updateStatusByIds() {
        // Given
        RequestOutbox box1 = outboxRepository.save(createOutbox(1L, OutboxStatus.PENDING));
        RequestOutbox box2 = outboxRepository.save(createOutbox(2L, OutboxStatus.PENDING));

        // When
        int updatedRows = outboxRepository.updateStatusByIds(
                OutboxStatus.SUCCESS, List.of(box1.getId(), box2.getId())
        );

        // Then
        assertThat(updatedRows).isEqualTo(2);

        // DB에서 최신 상태 조회 (영속성 컨텍스트 초기화 효과 확인)
        assertThat(outboxRepository.findById(box1.getId()).get().getStatus()).isEqualTo(OutboxStatus.SUCCESS);
    }

    @Test
    @DisplayName("서버 B의 실패 결과(TraceId)를 받아 원자적으로 COMPENSATED 상태로 변경한다.")
    void markAsCompensatedAtomically() {
        // Given
        String traceId = UUID.randomUUID().toString();
        RequestOutbox box = outboxRepository.save(RequestOutbox.builder()
                                                               .traceId(traceId).userId(1L).goodsId(1L).status(OutboxStatus.SENT).build());

        // When
        int updatedRows = outboxRepository.markAsCompensatedAtomically(traceId);

        // Then
        assertThat(updatedRows).isEqualTo(1);
        assertThat(outboxRepository.findById(box.getId()).get().getStatus()).isEqualTo(OutboxStatus.COMPENSATED);
    }

    @Test
    @DisplayName("SKIP LOCKED 힌트가 적용된 조회 쿼리가 문법 오류 없이 실행된다.")
    void findClaimableRecords() {
        // Given
        outboxRepository.save(createOutbox(1L, OutboxStatus.PENDING));
        outboxRepository.save(createOutbox(2L, OutboxStatus.PENDING));

        // When (동시성 락 테스트는 단위 테스트에서 어렵지만, 쿼리 문법 자체가 올바른지 검증)
        List<RequestOutbox> records = outboxRepository.findClaimableRecords(OutboxStatus.PENDING.name(), 10);

        // Then
        assertThat(records).hasSize(2);
    }

    @Test
    @DisplayName("품절 일괄 FAIL: 품절 goodsId의 PENDING 레코드를 한 번의 쿼리로 전부 FAIL 처리한다.")
    void bulkFailPendingByGoodsIds_failsOnlySoldOutGoods() {
        // Given: goodsId=1(품절 대상) PENDING 2건, goodsId=2(정상) PENDING 1건, SUCCESS 1건
        RequestOutbox soldOut1 = outboxRepository.save(createOutboxWithGoods(1L, 1L, OutboxStatus.PENDING));
        RequestOutbox soldOut2 = outboxRepository.save(createOutboxWithGoods(2L, 1L, OutboxStatus.PENDING));
        RequestOutbox normal   = outboxRepository.save(createOutboxWithGoods(3L, 2L, OutboxStatus.PENDING));
        RequestOutbox success  = outboxRepository.save(createOutboxWithGoods(4L, 1L, OutboxStatus.SUCCESS));

        // When: goodsId=1만 품절 처리
        int failed = outboxRepository.bulkFailPendingByGoodsIds(List.of(1L));

        // Then
        assertThat(failed).isEqualTo(2);
        assertThat(outboxRepository.findById(soldOut1.getId()).get().getStatus()).isEqualTo(OutboxStatus.FAIL);
        assertThat(outboxRepository.findById(soldOut2.getId()).get().getStatus()).isEqualTo(OutboxStatus.FAIL);
        assertThat(outboxRepository.findById(normal.getId()).get().getStatus()).isEqualTo(OutboxStatus.PENDING);  // 유지
        assertThat(outboxRepository.findById(success.getId()).get().getStatus()).isEqualTo(OutboxStatus.SUCCESS); // 유지
    }

    @Test
    @DisplayName("좀비 복구: PUBLISHING 상태이면서 thresholdTime 이전에 업데이트된 레코드를 PENDING으로 복구한다.")
    void recoverZombieMessages_restoresPendingStatus() {
        // Given: PUBLISHING 상태로 저장 후 DB에 반영
        RequestOutbox publishing = outboxRepository.save(createOutbox(99L, OutboxStatus.PUBLISHING));
        testEntityManager.flush();

        // updated_at을 60초 전으로 강제 변경 (JPA Auditing이 현재 시각으로 세팅하므로 직접 조작)
        testEntityManager.getEntityManager()
                         .createNativeQuery("UPDATE request_outbox SET updated_at = :time WHERE id = :id")
                         .setParameter("time", LocalDateTime.now().minusSeconds(60))
                         .setParameter("id", publishing.getId())
                         .executeUpdate();
        testEntityManager.clear(); // L1 캐시 초기화 → 다음 읽기가 DB에서 가져오도록

        // When
        int recovered = outboxRepository.recoverZombieMessages(LocalDateTime.now());

        // Then
        assertThat(recovered).isEqualTo(1);
        assertThat(outboxRepository.findById(publishing.getId()).get().getStatus())
                .isEqualTo(OutboxStatus.PENDING);
    }

    @Test
    @DisplayName("좀비 복구: 방금 생성된(thresholdTime보다 최신) PUBLISHING 레코드는 복구 대상에서 제외된다.")
    void recoverZombieMessages_doesNotRestoreRecentMessages() {
        // Given: 방금 저장된 PUBLISHING 레코드 (updated_at ≈ now())
        outboxRepository.save(createOutbox(98L, OutboxStatus.PUBLISHING));

        // When: 30초 전 기준으로 복구 시도 → 방금 만든 레코드는 해당 없음
        int recovered = outboxRepository.recoverZombieMessages(LocalDateTime.now().minusSeconds(30));

        // Then
        assertThat(recovered).isEqualTo(0);
    }

    @Test
    @DisplayName("좀비 복구: PUBLISHING 상태만 복구 대상이고, SUCCESS·FAIL 상태 레코드는 변경되지 않는다.")
    void recoverZombieMessages_onlyRestoresPublishingStatus() {
        // Given: 다양한 상태의 레코드 저장
        RequestOutbox successBox = outboxRepository.save(createOutbox(1L, OutboxStatus.SUCCESS));
        RequestOutbox failBox = outboxRepository.save(createOutbox(2L, OutboxStatus.FAIL));
        RequestOutbox publishingBox = outboxRepository.save(createOutbox(3L, OutboxStatus.PUBLISHING));
        testEntityManager.flush();

        // PUBLISHING 레코드의 updated_at만 과거로 변경
        testEntityManager.getEntityManager()
                         .createNativeQuery("UPDATE request_outbox SET updated_at = :time WHERE id = :id")
                         .setParameter("time", LocalDateTime.now().minusSeconds(60))
                         .setParameter("id", publishingBox.getId())
                         .executeUpdate();
        testEntityManager.clear();

        // When
        int recovered = outboxRepository.recoverZombieMessages(LocalDateTime.now());

        // Then: PUBLISHING만 PENDING으로 변경, 나머지 상태는 그대로
        assertThat(recovered).isEqualTo(1);
        assertThat(outboxRepository.findById(publishingBox.getId()).get().getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxRepository.findById(successBox.getId()).get().getStatus()).isEqualTo(OutboxStatus.SUCCESS);
        assertThat(outboxRepository.findById(failBox.getId()).get().getStatus()).isEqualTo(OutboxStatus.FAIL);
    }

    // 테스트 데이터 생성용 헬퍼 메서드 (필수 값인 traceId 자동 생성)
    private RequestOutbox createOutbox(Long userId, OutboxStatus status) {
        return RequestOutbox.builder()
                            .traceId(UUID.randomUUID().toString())
                            .userId(userId)
                            .goodsId(1L)
                            .quantity(1)
                            .status(status)
                            .build();
    }

    private RequestOutbox createOutboxWithGoods(Long userId, Long goodsId, OutboxStatus status) {
        return RequestOutbox.builder()
                            .traceId(UUID.randomUUID().toString())
                            .userId(userId)
                            .goodsId(goodsId)
                            .quantity(1)
                            .status(status)
                            .build();
    }
}