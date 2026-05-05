package weverse.serverC;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import weverse.serverC.dto.PurchaseMessage;
import weverse.serverC.service.PgClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        // 💡 테스트 컨테이너가 켜지면 JPA가 알아서 final_order 테이블을 생성하도록 지시!
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@AutoConfigureMockMvc
@Testcontainers // 👈 Testcontainers 기능 활성화
class ServerCIntegrationTest {

    // 💡 [핵심] 테스트 실행 시 내 컴퓨터에 가상의 MySQL 8.0 컨테이너를 띄움!
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("testdb")
            .withUsername("test")
            .withPassword("test");

    // 💡 띄워진 MySQL 컨테이너의 랜덤 포트와 주소를 스프링 환경변수에 자동 주입
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    // 외부 PG사 API 통신만 가짜 객체(Mock)로 차단!
    @MockitoBean private PgClient pgClient;

    @Test
    @DisplayName("통합 테스트: 벌크 결제 요청이 들어오면 PG 승인 후 MySQL DB에 성공적으로 저장된다.")
    void bulkOrderIntegrationTest() throws Exception {
        // Given
        PurchaseMessage msg1 = new PurchaseMessage("trace-1", 1L, 1L, 1, "CARD", "ADDR", "123", "010", "A", "M", "IP");
        PurchaseMessage msg2 = new PurchaseMessage("trace-2", 2L, 1L, 2, "CARD", "ADDR", "123", "010", "B", "M", "IP");
        List<PurchaseMessage> payloads = List.of(msg1, msg2);

        // 가짜 PG사가 모두 결제 성공(빈 실패 리스트)을 반환한다고 설정
        given(pgClient.processPayments(anyList())).willReturn(List.of());

        // When: 서버 C로 API 요청 발송
        mockMvc.perform(post("/api/v1/c/orders/bulk")
                       .contentType(MediaType.APPLICATION_JSON)
                       .content(objectMapper.writeValueAsString(payloads)))
               .andExpect(status().isOk());

        // Then: 진짜 MySQL DB에 데이터가 정확히 들어갔는지 직접 쿼리하여 검증!
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM final_order", Integer.class);
        assertThat(count).isEqualTo(2);

        // 상태가 'SUCCESS'로 잘 업데이트 되었는지 확인
        String status = jdbcTemplate.queryForObject("SELECT status FROM final_order WHERE trace_id = 'trace-1'", String.class);
        assertThat(status).isEqualTo("SUCCESS");
    }
}