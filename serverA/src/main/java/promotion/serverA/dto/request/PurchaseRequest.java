package promotion.serverA.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PurchaseRequest(
        @NotNull(message = "userId는 필수입니다.")
        Long userId,

        @NotNull(message = "goodsId는 필수입니다.")
        Long goodsId,

        @Min(value = 1, message = "수량은 1개 이상이어야 합니다.")
        int quantity,

        @NotBlank(message = "결제 수단은 필수입니다.")
        String paymentMethod,

        @NotBlank(message = "배송지 주소는 필수입니다.")
        String shippingAddress,

        @NotBlank(message = "우편번호는 필수입니다.")
        String zipCode,

        @NotBlank(message = "전화번호는 필수입니다.")
        String phoneNumber,

        @NotBlank(message = "이메일은 필수입니다.")
        String email,

        String deliveryMemo,

        @NotBlank(message = "IP 주소는 필수입니다.")
        String clientIp
) {}