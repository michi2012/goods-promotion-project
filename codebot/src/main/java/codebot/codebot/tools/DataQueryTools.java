package codebot.codebot.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class DataQueryTools {

    private static final Pattern SELECT_PATTERN = Pattern.compile("^\\s*SELECT\\s", Pattern.CASE_INSENSITIVE);
    private static final Pattern LIMIT_PATTERN = Pattern.compile("\\bLIMIT\\s+\\d+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_REF_PATTERN =
            Pattern.compile("\\b(?:FROM|JOIN)\\s+[`\"]?([a-zA-Z_][a-zA-Z0-9_]*)[`\"]?", Pattern.CASE_INSENSITIVE);
    private static final int DEFAULT_LIMIT = 100;

    private static final Map<String, Set<String>> WHITELISTED_TABLES = Map.of(
            "order", Set.of("orders", "goods"),
            "payment", Set.of("payments"),
            "user", Set.of("users")
    );

    private final JdbcTemplate orderJdbcTemplate;
    private final JdbcTemplate paymentJdbcTemplate;
    private final JdbcTemplate userJdbcTemplate;

    public DataQueryTools(
            @Qualifier("orderJdbcTemplate") JdbcTemplate orderJdbcTemplate,
            @Qualifier("paymentJdbcTemplate") JdbcTemplate paymentJdbcTemplate,
            @Qualifier("userJdbcTemplate") JdbcTemplate userJdbcTemplate) {
        this.orderJdbcTemplate = orderJdbcTemplate;
        this.paymentJdbcTemplate = paymentJdbcTemplate;
        this.userJdbcTemplate = userJdbcTemplate;
    }

    @Tool(description = """
            order/payment/user DB에서 읽기 전용 SELECT 쿼리를 실행해 데이터를 조회합니다.
            언제 호출: PO/기획이 주문·결제·사용자 통계나 현황을 질문할 때.

            ## 조회 가능한 테이블/컬럼 (화이트리스트 — 이 외 테이블/컬럼은 사용할 수 없습니다)
            - order DB
              - orders: id, order_id, user_id, goods_id, quantity, payment_method, status, created_at, updated_at
                (status 값 예시: CREATED, PAID, FAILED, EXPIRED)
              - goods: id, name, stock
            - payment DB
              - payments: id, order_id, user_id, goods_id, quantity, payment_method, status, created_at
            - user DB
              - users: id, user_id, username, role, created_at, updated_at

            ## 규칙
            - sql은 SELECT로 시작하는 단일 문장만 허용됩니다 (세미콜론으로 여러 문장 연결 금지).
            - 테이블명은 스키마 접두사 없이 위 화이트리스트 이름 그대로 사용하세요 (예: FROM orders).
            - `SELECT *`는 절대 사용하지 마세요. 위 화이트리스트에 명시된 컬럼명만 콤마로 나열하세요
              (예: SELECT status, COUNT(*) AS cnt FROM orders GROUP BY status).
              화이트리스트 외 컬럼(email, password, phone_number, shipping_address 등 개인정보)은
              `SELECT *` 또는 직접 지정 시 DB 권한 오류로 실패합니다.
            - LIMIT을 지정하지 않으면 자동으로 LIMIT 100이 적용됩니다.

            반환: 코드블럭(```)으로 감싼 고정폭 텍스트 표. 실패 시 사유를 설명하는 문자열.
            """)
    public String executeQuery(
            @ToolParam(description = "조회 대상 DB: order, payment, user 중 하나") String database,
            @ToolParam(description = "실행할 단일 SELECT SQL문") String sql) {
        try {
            String db = database == null ? "" : database.trim().toLowerCase();
            JdbcTemplate jdbcTemplate = resolveJdbcTemplate(db);
            String validatedSql = validateAndNormalize(db, sql);
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(validatedSql);
            return toCodeBlockTable(rows);
        } catch (IllegalArgumentException e) {
            return "조회 실패: " + e.getMessage();
        } catch (Exception e) {
            log.warn("[DataQuery] 쿼리 실행 실패: {}", e.getMessage());
            return "조회 실패: " + e.getMessage();
        }
    }

    private JdbcTemplate resolveJdbcTemplate(String database) {
        return switch (database) {
            case "order" -> orderJdbcTemplate;
            case "payment" -> paymentJdbcTemplate;
            case "user" -> userJdbcTemplate;
            default -> throw new IllegalArgumentException(
                    "알 수 없는 database입니다: '" + database + "' (order/payment/user 중 하나여야 합니다)");
        };
    }

    private String validateAndNormalize(String database, String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql이 비어있습니다.");
        }
        String trimmed = sql.trim();
        if (!SELECT_PATTERN.matcher(trimmed).find()) {
            throw new IllegalArgumentException("SELECT문만 허용됩니다.");
        }

        String normalized = trimmed.endsWith(";") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        if (normalized.contains(";")) {
            throw new IllegalArgumentException("단일 SQL 문장만 허용됩니다 (세미콜론으로 여러 문장 연결 불가).");
        }

        Set<String> allowedTables = WHITELISTED_TABLES.get(database);
        String invalidTable = findNonWhitelistedTable(normalized, allowedTables);
        if (invalidTable != null) {
            throw new IllegalArgumentException(
                    "화이트리스트에 없는 테이블(" + invalidTable + ")을 참조하고 있습니다. 허용 테이블: " + allowedTables);
        }

        if (!LIMIT_PATTERN.matcher(normalized).find()) {
            normalized = normalized + " LIMIT " + DEFAULT_LIMIT;
        }
        return normalized;
    }

    private String findNonWhitelistedTable(String sql, Set<String> allowedTables) {
        Matcher matcher = TABLE_REF_PATTERN.matcher(sql);
        while (matcher.find()) {
            String table = matcher.group(1).toLowerCase();
            if (!allowedTables.contains(table)) {
                return table;
            }
        }
        return null;
    }

    private String toCodeBlockTable(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "```\n(결과 없음)\n```";
        }

        List<String> columns = new ArrayList<>(rows.get(0).keySet());
        int[] widths = new int[columns.size()];
        for (int i = 0; i < columns.size(); i++) {
            widths[i] = columns.get(i).length();
        }

        List<String[]> cellRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String[] cells = new String[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                Object value = row.get(columns.get(i));
                cells[i] = value == null ? "" : value.toString();
                widths[i] = Math.max(widths[i], cells[i].length());
            }
            cellRows.add(cells);
        }

        StringBuilder sb = new StringBuilder("```\n");
        appendRow(sb, columns.toArray(new String[0]), widths);
        appendSeparator(sb, widths);
        for (String[] cells : cellRows) {
            appendRow(sb, cells, widths);
        }
        sb.append("```");
        return truncate(sb.toString());
    }

    private void appendRow(StringBuilder sb, String[] cells, int[] widths) {
        for (int i = 0; i < cells.length; i++) {
            sb.append(String.format("%-" + widths[i] + "s", cells[i]));
            if (i < cells.length - 1) {
                sb.append(" | ");
            }
        }
        sb.append("\n");
    }

    private void appendSeparator(StringBuilder sb, int[] widths) {
        for (int i = 0; i < widths.length; i++) {
            sb.append("-".repeat(widths[i]));
            if (i < widths.length - 1) {
                sb.append("-+-");
            }
        }
        sb.append("\n");
    }

    private String truncate(String data) {
        int maxLength = 10000;
        if (data.length() > maxLength) {
            return data.substring(0, maxLength) + "\n...[데이터가 너무 길어 시스템 보호를 위해 절삭되었습니다]...";
        }
        return data;
    }
}
