package aiops.aiops.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(MockitoExtension.class)
@DisplayName("DltTools 단위 테스트")
class DltToolsTest {

    private MockRestServiceServer server;
    private DltTools dltTools;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://server-a:8080");
        server = MockRestServiceServer.bindTo(builder).build();
        dltTools = new DltTools(builder.build(), new ObjectMapper());
    }

    @Test
    @DisplayName("listUnresolvedDlt: UNRESOLVED DLT 목록을 JSON으로 반환한다")
    void listUnresolvedDlt_성공() {
        server.expect(requestTo("http://server-a:8080/api/v1/admin/dlt"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {"id":1,"orderId":"order-1","goodsId":10,"quantity":2,"reason":"재고 부족","status":"UNRESOLVED"},
                          {"id":2,"orderId":"UNKNOWN","goodsId":null,"quantity":1,"reason":"스키마 오류","status":"UNRESOLVED"}
                        ]
                        """, MediaType.APPLICATION_JSON));

        String result = dltTools.listUnresolvedDlt();

        assertThat(result).contains("order-1").contains("UNKNOWN");
    }

    @Test
    @DisplayName("listUnresolvedDlt: serverA 장애 시 실패 메시지를 반환한다")
    void listUnresolvedDlt_서버장애() {
        server.expect(requestTo("http://server-a:8080/api/v1/admin/dlt"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withServerError());

        String result = dltTools.listUnresolvedDlt();

        assertThat(result).contains("조회 실패");
    }

    @Test
    @DisplayName("retryDlt: 재처리 성공 시 완료 메시지를 반환한다")
    void retryDlt_성공() {
        server.expect(requestTo("http://server-a:8080/api/v1/admin/dlt/1/retry"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("DLT Re-processed Successfully", MediaType.TEXT_PLAIN));

        String result = dltTools.retryDlt(1L);

        assertThat(result).contains("재처리 완료").contains("1");
    }

    @Test
    @DisplayName("retryDlt: serverA 오류 시 실패 메시지와 수동 처리 안내를 반환한다")
    void retryDlt_실패() {
        server.expect(requestTo("http://server-a:8080/api/v1/admin/dlt/99/retry"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        String result = dltTools.retryDlt(99L);

        assertThat(result).contains("재처리 실패").contains("수동 처리");
    }
}
