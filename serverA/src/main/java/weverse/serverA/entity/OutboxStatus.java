package weverse.serverA.entity;

public enum OutboxStatus {
    PENDING,     // 1. 최초 접수됨
    SUCCESS,     // 2. 재고 차감 성공 (발송 대기)
    FAIL,        // 2-1. 재고 차감 실패 (품절, 중복 등)
    PUBLISHING,  // 3. 서버 B로 발송 중 (스케줄러가 찜함)
    SENT,         // 4. 서버 B 수신 완료 (최종)
    COMPENSATED // 💡 수동/자동으로 재고 롤백이 완료된 상태 추가
}