package weverse.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import weverse.serverA.dto.request.CreateGoodsRequest;
import weverse.serverA.dto.response.CreateGoodsResponse;
import weverse.serverA.entity.Goods;
import weverse.serverA.repository.GoodsRepository;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class GoodsService {

    private final GoodsRepository goodsRepository;

    private final ConcurrentMap<Long, Boolean> soldOutCache = new ConcurrentHashMap<>();

    @Transactional
    public CreateGoodsResponse createGoods(CreateGoodsRequest createGoodsRequest) {
        log.info("[GoodsService] 상품 엔티티 생성 시작: {}", createGoodsRequest.name());

        Goods goods = Goods.builder()
                           .name(createGoodsRequest.name())
                           .stock(createGoodsRequest.stock())
                           .build();

        Goods savedGoods = goodsRepository.save(goods);

        log.info("[GoodsService] 상품 DB 저장 완료: id={}", savedGoods.getId());

        return new CreateGoodsResponse(
                savedGoods.getId(),
                savedGoods.getName(),
                savedGoods.getStock()
        );

    }

    public boolean isSoldOut(Long goodsId) {
        return soldOutCache.getOrDefault(goodsId, false);
    }

    @Scheduled(fixedDelay = 1000)
    public void syncSoldOutStatus() {
        try {
            List<Long> soldOutGoodsIds = goodsRepository.findSoldOutGoodsIds();

            for (Long goodsId : soldOutGoodsIds) {
                if (soldOutCache.putIfAbsent(goodsId, true) == null) {
                    log.info("[GoodsService] 상품 ID: {} 품절 로컬 캐시 동기화 완료", goodsId);
                }
            }
        } catch (Exception e) {
            log.error("[GoodsService] 품절 상태 동기화 중 에러 발생: {}", e.getMessage());
        }
    }
}
