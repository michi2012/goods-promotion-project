package weverse.serverA.exception;

// 💡 2. 구체적인 예외들
public class SoldOutException extends BusinessException {
    public SoldOutException() {
        super("상품이 품절되었습니다.", "SOLD_OUT");
    }
}
