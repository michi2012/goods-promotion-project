package promotion.serverC.batch;

/**
 * 대사 불일치 유형 (명세 P-4, 이벤트 단위 판정).
 */
public enum MismatchType {
    /** ① 우리 이벤트에 대응하는 PG 이벤트가 없음 */
    OURS_ONLY,
    /** ② PG 이벤트의 orderId가 우리 DB에 없음 */
    PG_ONLY,
    /** ③ 같은 이벤트인데 금액이 다름 */
    AMOUNT,
    /** ④ PG 이벤트는 있고 우리 결제 건도 있으나 그날 그 이벤트가 없음 */
    STATUS
}
