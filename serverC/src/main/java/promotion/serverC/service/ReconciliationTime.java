package promotion.serverC.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * PG 대사 기준 시각. 대사 범위(하루)가 KST 기준이므로, 결제 승인·취소 시각과 모의 PG 원장 시각을
 * JVM 기본 타임존(배포 환경마다 다를 수 있음)과 무관하게 KST 벽시계로 찍는다.
 */
public final class ReconciliationTime {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private ReconciliationTime() {
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
