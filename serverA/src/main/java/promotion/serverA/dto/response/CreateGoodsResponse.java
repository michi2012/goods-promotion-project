package promotion.serverA.dto.response;

public record CreateGoodsResponse(
        Long id,
        String name,
        long price,
        int stock
) {

}
