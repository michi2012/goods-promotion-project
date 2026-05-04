package weverse.serverA.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class EventNotifierTest {

    @InjectMocks
    private EventNotifier eventNotifier;

    @Mock
    private RestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(eventNotifier, "serverBUrl", "http://localhost:8081");
    }

    @Test
    @DisplayName("동시성: 100개의 스레드가 동시에 품절 알림을 시도해도 서버 B로는 딱 1번만 API가 전송된다.")
    void notifySoldOutToServerB_Concurrency() throws InterruptedException {
        // Given
        Long targetGoodsId = 1L;
        int threadCount = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        // When: 100개의 스레드가 동시에 이벤트를 쏜다
        for (int i = 0; i < threadCount; i++) {
            executorService.submit(() -> {
                try {
                    eventNotifier.notifySoldOutToServerB(targetGoodsId);
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // Then: RestTemplate.postForEntity는 단 1번만 호출되어야 함! (원자적 방어 성공)
        verify(restTemplate, times(1)).postForEntity(anyString(), any(), eq(String.class));
    }
}