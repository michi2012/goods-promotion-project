package weverse.serverA;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.client.RestTemplate;
import weverse.serverA.dto.PurchaseMessage;
import weverse.serverA.entity.Goods;
import weverse.serverA.entity.OutboxStatus;
import weverse.serverA.entity.RequestOutbox;
import weverse.serverA.repository.GoodsRepository;
import weverse.serverA.repository.OutboxRepository;
import weverse.serverA.service.PromotionService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ServerAIntegrationTest {

    @Autowired
    private PromotionService promotionService;

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @MockitoBean
    private RestTemplate restTemplate;

    private Long testGoodsId;
    private String testTraceId;

    @BeforeEach
    void setUp() {
        Goods goods = goodsRepository.save(new Goods("위버스 통합테스트 포토카드", 10));
        testGoodsId = goods.getId();
        testTraceId = UUID.randomUUID().toString();
    }

    @AfterEach
    void tearDown() {
        outboxRepository.deleteAllInBatch();
        goodsRepository.deleteAllInBatch();
    }

    @Test
    @DisplayName("통합 파이프라인: [즉시 응답 -> 벌크 인서트 -> 재고 차감 검증]이 하나의 흐름으로 성공해야 한다.")
    void fullPurchasePipeline_Success() throws Exception {
        // ==========================================
        // 1단계: API 요청 수신 및 즉시 응답 (비동기 큐 진입)
        // ==========================================
        PurchaseMessage message = new PurchaseMessage(
                testTraceId, 100L, testGoodsId, 2, "CARD",
                "서울시 강남구", "12345", "010-1234-5678", "test@test.com", "빨리주세요", "127.0.0.1"
        );

        ResponseEntity<String> response = promotionService.acceptPurchase(message);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        // 아직 플러시 전이므로 DB에는 반영되지 않아야 함
        assertThat(outboxRepository.findAll()).isEmpty();

        // ==========================================
        // 2단계: 큐의 데이터를 DB(Outbox)로 플러시 (벌크 인서트)
        // ==========================================
        promotionService.flushToOutbox();

        // DB에 PENDING 상태로 정확히 1건 저장되었는지 확인
        List<RequestOutbox> savedOutboxes = outboxRepository.findAll();
        assertThat(savedOutboxes).hasSize(1);
        assertThat(savedOutboxes.get(0).getStatus()).isEqualTo(OutboxStatus.PENDING);

        // ==========================================
        // 3단계: 아웃박스 프로세서가 PENDING 데이터를 읽어 재고를 차감하고 SUCCESS로 변경
        // ==========================================
        promotionService.processPendingRequests();

        // 최종 상태 검증
        RequestOutbox processedOutbox = outboxRepository.findById(savedOutboxes.get(0).getId()).orElseThrow();
        assertThat(processedOutbox.getStatus()).isEqualTo(OutboxStatus.SUCCESS);

        int remainStock = goodsRepository.findStockById(testGoodsId);
        assertThat(remainStock).isEqualTo(8);
    }
}