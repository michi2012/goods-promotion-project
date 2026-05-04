package weverse.serverA.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OutboxRepositoryTest {

    @Autowired
    private OutboxRepository outboxRepository;

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
                                                               .traceId(traceId).userId(1L).goodsId(1L).status(OutboxStatus.SUCCESS).build());

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
        List<RequestOutbox> records = outboxRepository.findClaimableRecords(
                OutboxStatus.PENDING, PageRequest.of(0, 10)
        );

        // Then
        assertThat(records).hasSize(2);
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
}