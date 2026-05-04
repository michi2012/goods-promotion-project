package weverse.serverA.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import weverse.serverA.entity.Goods;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GoodsRepositoryTest {

    @Autowired
    private GoodsRepository goodsRepository;

    @Test
    @DisplayName("재고 감소 성공: 남아있는 재고가 차감하려는 수량보다 많거나 같으면 차감되고 1(업데이트된 행 수)을 반환한다.")
    void decreaseStockAtomically_Success() {
        // Given
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 10));

        // When
        int updatedRows = goodsRepository.decreaseStockAtomically(goods.getId(), 3);

        // Then
        assertThat(updatedRows).isEqualTo(1); // 1개의 행이 업데이트 됨

        int currentStock = goodsRepository.findStockById(goods.getId());
        assertThat(currentStock).isEqualTo(7); // 10 - 3 = 7
    }

    @Test
    @DisplayName("재고 감소 실패: 차감하려는 수량이 남은 재고보다 많으면 업데이트되지 않고 0을 반환한다.")
    void decreaseStockAtomically_Fail_WhenNotEnoughStock() {
        // Given
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 2));

        // When (재고 2개인데 5개 차감 시도)
        int updatedRows = goodsRepository.decreaseStockAtomically(goods.getId(), 5);

        // Then
        assertThat(updatedRows).isEqualTo(0); // 업데이트된 행이 없음 (방어 성공)

        int currentStock = goodsRepository.findStockById(goods.getId());
        assertThat(currentStock).isEqualTo(2); // 재고가 그대로 유지됨
    }

    @Test
    @DisplayName("재고 증가: 서버 B의 보상 트랜잭션 요청 시 재고를 다시 원상 복구한다.")
    void increaseStockAtomically_Success() {
        // Given
        Goods goods = goodsRepository.save(new Goods("테스트 상품", 5));

        // When
        int updatedRows = goodsRepository.increaseStockAtomically(goods.getId(), 2);

        // Then
        assertThat(updatedRows).isEqualTo(1);
        int currentStock = goodsRepository.findStockById(goods.getId());
        assertThat(currentStock).isEqualTo(7); // 5 + 2 = 7
    }
}