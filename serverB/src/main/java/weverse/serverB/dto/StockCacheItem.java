package weverse.serverB.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StockCacheItem {
    private final int stock;       // 남은 재고 수량
    private final long expireAt;   // 이 데이터가 만료되는 시간 (밀리초)
}