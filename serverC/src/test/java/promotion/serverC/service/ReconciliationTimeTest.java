package promotion.serverC.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationTimeTest {

    @Test
    @DisplayName("JVM 기본 타임존이 UTC여도 KST 벽시계를 돌려준다 (파드 TZ 미설정 대비)")
    void nowIsKstRegardlessOfDefaultZone() {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

            LocalDateTime now = ReconciliationTime.now();

            LocalDateTime kst = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            assertThat(Duration.between(now, kst).abs()).isLessThan(Duration.ofSeconds(5));
            assertThat(Duration.between(now, LocalDateTime.now()).abs()).isGreaterThan(Duration.ofHours(8));
        } finally {
            TimeZone.setDefault(original);
        }
    }
}
