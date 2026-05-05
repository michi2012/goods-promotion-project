package weverse.serverA.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import weverse.serverA.dto.request.CreateGoodsRequest;
import weverse.serverA.dto.response.CreateGoodsResponse;
import weverse.serverA.service.GoodsService;

@RestController
@RequestMapping("/api/v1/goods")
@RequiredArgsConstructor
@Slf4j
public class GoodsController {

    private final GoodsService goodsService;

    @PostMapping
    public ResponseEntity<CreateGoodsResponse> createGoods(@RequestBody CreateGoodsRequest createGoodsRequest) {
        log.info("[GoodsController] 상품 생성 요청 수신: name={}", createGoodsRequest.name());

        CreateGoodsResponse createGoodsResponse = goodsService.createGoods(createGoodsRequest);

        log.info("[GoodsController] 상품 생성 완료: id={}", createGoodsResponse.id());
        return ResponseEntity.ok(createGoodsResponse);
    }

}
