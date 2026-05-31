package promotion.serverA.exception;

// 💡 DLT 기록을 찾을 수 없을 때
public class DltNotFoundException extends BusinessException {
    public DltNotFoundException() {
        super("해당 DLT 기록을 찾을 수 없습니다.", "DLT_NOT_FOUND");
    }
}
