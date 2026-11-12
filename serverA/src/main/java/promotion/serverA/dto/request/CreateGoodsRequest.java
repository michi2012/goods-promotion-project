package promotion.serverA.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record CreateGoodsRequest(
        String name,

        // 원 단위 가격. 0원 상품은 구매가 차단되므로 생성 시점에 1원 이상을 강제한다.
        @NotNull(message = "가격은 필수입니다.")
        @Min(value = 1, message = "가격은 1원 이상이어야 합니다.")
        Long price,

        Integer stock
) {
}
