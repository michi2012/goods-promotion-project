package promotion.serverC.exception;

import java.time.LocalDate;

public class ReconciliationAlreadyRunningException extends RuntimeException {
    public ReconciliationAlreadyRunningException(LocalDate reconDate) {
        super("같은 날짜의 대사가 실행 중입니다: " + reconDate);
    }
}
