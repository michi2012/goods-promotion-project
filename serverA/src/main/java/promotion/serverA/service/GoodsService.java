package promotion.serverA.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import promotion.serverA.dto.request.CreateGoodsRequest;
import promotion.serverA.dto.response.CreateGoodsResponse;
import promotion.serverA.entity.Goods;
import promotion.serverA.repository.GoodsRepository;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class GoodsService {

    private final GoodsRepository goodsRepository;
    private final RedisStockService redisStockService;

    @Transactional
    public CreateGoodsResponse createGoods(CreateGoodsRequest createGoodsRequest) {
        log.info("[GoodsService] 상품 엔티티 생성 시작: {}", createGoodsRequest.name());

        Goods goods = Goods.builder()
                           .name(createGoodsRequest.name())
                           .stock(createGoodsRequest.stock())
                           .build();

        Goods savedGoods = goodsRepository.save(goods);
        redisStockService.initStock(savedGoods.getId(), savedGoods.getStock());

        log.info("[GoodsService] 상품 DB 저장 완료: id={}", savedGoods.getId());

        return new CreateGoodsResponse(
                savedGoods.getId(),
                savedGoods.getName(),
                savedGoods.getStock()
        );
    }

}
