package weverse.serverA.exception;

public class GoodsNotFoundException extends BusinessException {
    public GoodsNotFoundException() {
        super("상품 정보를 찾을 수 없습니다.", "GOODS_NOT_FOUND");
    }
}
