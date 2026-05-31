package promotion.serverA.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import promotion.serverA.entity.Goods;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class GoodsRepositoryTest {

    @Container
    static MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private GoodsRepository goodsRepository;

    @Test
    @DisplayName("재고 감소 성공: 남아있는 재고가 차감하려는 수량보다 많거나 같으면 차감되고 1(업데이트된 행 수)을 반환한다.")
    void decreaseStockAtomically_Success() {
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 10));

        int updatedRows = goodsRepository.decreaseStockAtomically(goods.getId(), 3);

        assertThat(updatedRows).isEqualTo(1);
        assertThat(goodsRepository.findStockById(goods.getId())).isEqualTo(7);
    }

    @Test
    @DisplayName("재고 감소 실패: 차감하려는 수량이 남은 재고보다 많으면 업데이트되지 않고 0을 반환한다.")
    void decreaseStockAtomically_Fail_WhenNotEnoughStock() {
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 2));

        int updatedRows = goodsRepository.decreaseStockAtomically(goods.getId(), 5);

        assertThat(updatedRows).isEqualTo(0);
        assertThat(goodsRepository.findStockById(goods.getId())).isEqualTo(2);
    }

    @Test
    @DisplayName("재고 증가: 서버 B의 보상 트랜잭션 요청 시 재고를 다시 원상 복구한다.")
    void increaseStockAtomically_Success() {
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 5));

        int updatedRows = goodsRepository.increaseStockAtomically(goods.getId(), 2);

        assertThat(updatedRows).isEqualTo(1);
        assertThat(goodsRepository.findStockById(goods.getId())).isEqualTo(7);
    }

    @Test
    @DisplayName("단순 재고 차감 성공: Redis 검증을 신뢰하고 WHERE 조건 없이 즉시 재고를 차감한다.")
    void decreaseStock_Success() {
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 10));

        goodsRepository.decreaseStock(goods.getId(), 3);

        assertThat(goodsRepository.findStockById(goods.getId())).isEqualTo(7);
    }
}
