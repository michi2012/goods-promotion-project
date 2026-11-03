package promotion.serverC.exception;

// PG 응답을 못 받은(타임아웃성) 상황 — 서킷브레이커 open(PgPaymentException, 확실한 장애)과 달리
// 결제가 실제로 처리됐는지 알 수 없는 애매한 상태. 재시도 전 조회로 먼저 확인해야 한다.
public class PgAmbiguousResponseException extends RuntimeException {
    public PgAmbiguousResponseException(String message) {
        super(message);
    }
}
