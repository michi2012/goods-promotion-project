package codebot.codebot.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("DataQueryTools(codebot) 단위 테스트")
class DataQueryToolsTest {

    private JdbcTemplate orderJdbcTemplate;
    private JdbcTemplate paymentJdbcTemplate;
    private JdbcTemplate userJdbcTemplate;
    private DataQueryTools dataQueryTools;

    @BeforeEach
    void setUp() {
        orderJdbcTemplate = mock(JdbcTemplate.class);
        paymentJdbcTemplate = mock(JdbcTemplate.class);
        userJdbcTemplate = mock(JdbcTemplate.class);
        dataQueryTools = new DataQueryTools(orderJdbcTemplate, paymentJdbcTemplate, userJdbcTemplate);
    }

    @Test
    @DisplayName("화이트리스트 테이블 조회 성공 시 LIMIT을 자동 추가하고 코드블럭 표를 반환한다")
    void executeQuery_성공_LIMIT자동추가_코드블럭표반환() {
        // given
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", 1);
        row.put("status", "PAID");
        when(orderJdbcTemplate.queryForList(anyString())).thenReturn(List.of(row));

        // when
        String result = dataQueryTools.executeQuery("order", "SELECT id, status FROM orders");

        // then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderJdbcTemplate).queryForList(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).contains("LIMIT 100");

        assertThat(result).startsWith("```");
        assertThat(result).contains("id").contains("status").contains("1").contains("PAID");
    }

    @Test
    @DisplayName("LIMIT이 이미 포함된 SQL은 LIMIT을 추가하지 않는다")
    void executeQuery_성공_LIMIT이미있으면_추가하지않음() {
        // given
        when(orderJdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        // when
        dataQueryTools.executeQuery("order", "SELECT id FROM orders LIMIT 10");

        // then
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(orderJdbcTemplate).queryForList(sqlCaptor.capture());
        assertThat(sqlCaptor.getValue()).containsOnlyOnce("LIMIT");
        assertThat(sqlCaptor.getValue()).contains("LIMIT 10");
    }

    @Test
    @DisplayName("결과가 없으면 결과 없음 메시지를 반환한다")
    void executeQuery_성공_결과없음() {
        // given
        when(orderJdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        // when
        String result = dataQueryTools.executeQuery("order", "SELECT id FROM orders");

        // then
        assertThat(result).isEqualTo("```\n(결과 없음)\n```");
    }

    @Test
    @DisplayName("SELECT로 시작하지 않으면 쿼리를 실행하지 않고 거부한다")
    void executeQuery_실패_SELECT아니면_거부() {
        // when
        String result = dataQueryTools.executeQuery("order", "DELETE FROM orders");

        // then
        assertThat(result).startsWith("조회 실패").contains("SELECT");
        verify(orderJdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    @DisplayName("세미콜론으로 여러 문장을 연결하면 거부한다")
    void executeQuery_실패_여러문장이면_거부() {
        // when
        String result = dataQueryTools.executeQuery("order", "SELECT * FROM orders; DROP TABLE orders");

        // then
        assertThat(result).startsWith("조회 실패").contains("단일 SQL 문장");
        verify(orderJdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    @DisplayName("화이트리스트에 없는 테이블을 참조하면 거부한다")
    void executeQuery_실패_화이트리스트밖테이블이면_거부() {
        // when
        String result = dataQueryTools.executeQuery("order", "SELECT * FROM users");

        // then
        assertThat(result).startsWith("조회 실패").contains("화이트리스트");
        verify(orderJdbcTemplate, never()).queryForList(anyString());
        verify(userJdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    @DisplayName("알 수 없는 database 값이면 거부한다")
    void executeQuery_실패_알수없는DB면_거부() {
        // when
        String result = dataQueryTools.executeQuery("unknown", "SELECT 1");

        // then
        assertThat(result).startsWith("조회 실패").contains("order/payment/user");
    }

    @Test
    @DisplayName("DB 조회 중 예외가 발생하면 실패 사유를 반환한다")
    void executeQuery_실패_DB예외시_사유반환() {
        // given
        when(paymentJdbcTemplate.queryForList(anyString()))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("connection refused"));

        // when
        String result = dataQueryTools.executeQuery("payment", "SELECT id FROM payments");

        // then
        assertThat(result).startsWith("조회 실패").contains("connection refused");
    }
}
