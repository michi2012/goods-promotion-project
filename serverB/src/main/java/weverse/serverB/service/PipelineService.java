package weverse.serverB.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import weverse.serverB.dto.PurchaseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final Cache<Long, Boolean> soldOutCache = Caffeine.newBuilder()
                                                              .maximumSize(10000)
                                                              .expireAfterWrite(1, TimeUnit.HOURS)
                                                              .build();

    private final Cache<Long, Integer> stockCache = Caffeine.newBuilder()
                                                            .maximumSize(10000)
                                                            .expireAfterWrite(1, TimeUnit.SECONDS)
                                                            .build();

    // 💡 1. 멱등성 검증, 상태 변경, 스트림 적재를 하나로 묶은 원자적 Lua 스크립트
    private static final String ATOMIC_PROCESS_SCRIPT =
            "if redis.call('SETNX', KEYS[1], 'OK') == 1 then\n" +       // 1) 최초 요청인지(멱등성) 원자적 확인
                    "    redis.call('EXPIRE', KEYS[1], ARGV[1])\n" +            // 2) 멱등성 키 TTL 설정
                    "    redis.call('HSET', KEYS[2], 'status', 'PROCESSING')\n" + // 3) 유저 상태 PROCESSING 변경
                    "    redis.call('XADD', KEYS[3], '*', 'payload', ARGV[2])\n" +// 4) Server C 전송용 큐 적재
                    "    return 1\n" + // 신규 처리 성공
                    "else\n" +
                    "    return 0\n" + // 중복 요청 (Skipped)
                    "end";

    public void processBulkData(List<PurchaseMessage> messages) {
        if (messages.isEmpty()) return;

        log.info("[PipelineService] 벌크 데이터 원자적 파이프라인 처리 시작: {}건", messages.size());

        RedisScript<Long> script = new DefaultRedisScript<>(ATOMIC_PROCESS_SCRIPT, Long.class);

        // 💡 2. 파이프라이닝 안에 Lua 스크립트 지시를 묶어서 1RTT로 전송 (네트워크 최적화 + 원자성)
        List<Object> results = redisTemplate.executePipelined(new SessionCallback<Object>() {
            @Override
            public Object execute(RedisOperations operations) {
                for (PurchaseMessage msg : messages) {
                    List<String> keys = List.of(
                            "trace:" + msg.traceId(),             // KEYS[1]
                            "user:" + msg.userId() + ":order",    // KEYS[2]
                            "queue:to_server_c"                   // KEYS[3]
                    );
                    String payload = toJson(msg);                 // ARGV[2]

                    operations.execute(script, keys, "3600", payload);
                }
                return null;
            }
        });

        // 💡 3. Lua 스크립트가 반환한 1(성공)과 0(중복)을 카운팅하여 정확한 메트릭 로깅
        long successCount = results.stream().filter(res -> (Long) res == 1L).count();
        log.info("[PipelineService] 처리 완료. 총 수신: {}건 / 신규 정상 처리: {}건 / 중복 필터링: {}건",
                messages.size(), successCount, messages.size() - successCount);
    }

    public String getUserOrderStatus(Long userId) {
        log.info("[PipelineService] 사용자 주문 상태 조회 요청: userId={}", userId);
        String userKey = "user:" + userId + ":order";

        // Redis Hash에서 'status' 필드의 값을 가져옴
        Object statusObj = redisTemplate.opsForHash().get(userKey, "status");

        if (statusObj != null) {
            log.info("[PipelineService] 주문 상태 조회 성공: userId={}, status={}", userId, statusObj);
            return statusObj.toString(); // 예: "PROCESSING", "SUCCESS", "FAIL"
        }

        log.info("[PipelineService] 주문 상태 찾을 수 없음 (미접수 또는 DB 이관됨): userId={}", userId);
        // Redis에 없으면 아직 접수가 안 됐거나, 너무 오래되어 DB로 넘어간 상태임
        return "NOT_FOUND";
    }

    public int getRemainingStock(Long goodsId) {
        log.info("[PipelineService] 상품 잔여 재고 조회 요청: goodsId={}", goodsId);

        if (isSoldOut(goodsId)) {
            log.info("[PipelineService] 상품 품절 상태로 확인됨: goodsId={}", goodsId);
            return 0;
        }

        // get(key, function)을 사용하면 Caffeine이 내부적으로 동기화 락을 겁니다.
        // 1만 명이 동시에 들어와도, 최초의 1명만 아래 람다식(Redis 조회)을 실행하고
        // 나머지 9,999명은 찰나의 대기 후 캐싱된 결과만 받아갑니다.
        Integer currentStock = stockCache.get(goodsId, key -> {
            log.info("[PipelineService] 로컬 캐시 미스 - Redis에서 재고 조회 진행: goodsId={}", key);
            String stockStr = redisTemplate.opsForValue().get("goods:" + key + ":stock");

            // 재고 정보가 없으면 함부로 0을 주지 않고 예외를 던짐 (또는 DB 조회를 시도)
            if (stockStr == null) {
                log.error("🚨 Redis에 재고 정보가 없습니다. Cache Pre-warming 상태를 확인하세요. 상품 ID: {}", key);
                throw new IllegalStateException("재고 정보를 불러올 수 없습니다.");
            }
            return Integer.parseInt(stockStr);
        });

        if (currentStock <= 0) {
            log.info("[PipelineService] 조회된 재고가 0 이하입니다. 품절 처리 진행: goodsId={}", goodsId);
            markGoodsAsSoldOut(goodsId);
            return 0;
        }

        log.info("[PipelineService] 상품 잔여 재고 반환: goodsId={}, currentStock={}", goodsId, currentStock);
        return currentStock;
    }

    public void markGoodsAsSoldOut(Long goodsId) {
        log.info("[PipelineService] 상품 품절 처리 시작: goodsId={}", goodsId);
        soldOutCache.put(goodsId, true);

        // 글로벌 품절 플래그에 24시간 TTL을 부여하여 Redis 메모리 보호
        redisTemplate.opsForValue().set("goods:" + goodsId + ":sold_out", "true", 24, TimeUnit.HOURS);
        log.warn("상품 [{}] 전역 품절 처리 완료 (TTL 24h 적용)", goodsId);
    }

    public boolean isSoldOut(Long goodsId) {
        Boolean isSoldOutLocal = soldOutCache.getIfPresent(goodsId);
        if (Boolean.TRUE.equals(isSoldOutLocal)) {
            log.info("[PipelineService] 로컬 캐시에서 품절 확인됨: goodsId={}", goodsId);
            return true;
        }

        String isSoldOutRedis = redisTemplate.opsForValue().get("goods:" + goodsId + ":sold_out");
        if ("true".equals(isSoldOutRedis)) {
            log.info("[PipelineService] Redis에서 품절 확인됨. 로컬 캐시 갱신: goodsId={}", goodsId);
            soldOutCache.put(goodsId, true);
            return true;
        }

        return false;
    }

    private String toJson(PurchaseMessage msg) {
        try {
            return objectMapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.error("[PipelineService] JSON 직렬화 실패: traceId={}", msg.traceId(), e);
            throw new RuntimeException("JSON Serialize Error", e);
        }
    }
}