package promotion.serverA.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import promotion.serverA.entity.Goods;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class GoodsRepositoryConcurrencyTest {

    @Autowired
    private GoodsRepository goodsRepository;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("과매도 방지: 재고 10개에 스레드 20개가 동시에 1개씩 차감 시도하면 정확히 10건만 성공하고 재고는 0이 된다.")
    void preventOverselling_20Threads_Stock10() throws InterruptedException {
        Goods goods = goodsRepository.save(new Goods("동시성 테스트 상품", 10));
        Long goodsId = goods.getId();

        try {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            TransactionTemplate txTemplate = new TransactionTemplate(txManager);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Integer updated = txTemplate.execute(status ->
                            goodsRepository.decreaseStockAtomically(goodsId, 1)
                    );
                    if (Integer.valueOf(1).equals(updated)) {
                        successCount.incrementAndGet();
                    }
                });
            }

            ready.await();
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            assertThat(successCount.get()).isEqualTo(10);
            assertThat(goodsRepository.findStockById(goodsId)).isEqualTo(0);
        } finally {
            goodsRepository.deleteById(goodsId);
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("과매도 방지: 재고 1개에 스레드 100개가 동시에 차감 시도하면 정확히 1건만 성공하고 음수 재고는 발생하지 않는다.")
    void preventOverselling_100Threads_Stock1() throws InterruptedException {
        Goods goods = goodsRepository.save(new Goods("마지막 재고 상품", 1));
        Long goodsId = goods.getId();

        try {
            int threadCount = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch ready = new CountDownLatch(threadCount);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger successCount = new AtomicInteger(0);
            TransactionTemplate txTemplate = new TransactionTemplate(txManager);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    Integer updated = txTemplate.execute(status ->
                            goodsRepository.decreaseStockAtomically(goodsId, 1)
                    );
                    if (Integer.valueOf(1).equals(updated)) {
                        successCount.incrementAndGet();
                    }
                });
            }

            ready.await();
            start.countDown();
            executor.shutdown();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));

            assertThat(successCount.get()).isEqualTo(1);
            assertThat(goodsRepository.findStockById(goodsId)).isEqualTo(0);
        } finally {
            goodsRepository.deleteById(goodsId);
        }
    }
}
