package weverse.serverA.exception;

// 💡 이미 관리자가 처리한 DLT를 다시 처리하려고 할 때
public class AlreadyResolvedDltException extends BusinessException {
    public AlreadyResolvedDltException() {
        super("이미 해결 처리된 DLT 내역입니다.", "ALREADY_RESOLVED_DLT");
    }
}
