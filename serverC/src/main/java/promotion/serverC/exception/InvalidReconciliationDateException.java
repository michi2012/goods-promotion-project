package promotion.serverC.exception;

import java.time.LocalDate;

public class InvalidReconciliationDateException extends RuntimeException {
    public InvalidReconciliationDateException(LocalDate reconDate) {
        super("어제까지만 대사할 수 있습니다 (그날 PG 파일은 다음 날 생성): " + reconDate);
    }
}
