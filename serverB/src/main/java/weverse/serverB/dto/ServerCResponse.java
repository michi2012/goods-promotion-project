package weverse.serverB.dto; // 💡 서버 B에 넣을 때는 weverse.serverB.dto 로 변경하세요!

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter // 💡 이 어노테이션이 getFailedTraceIds() 등을 자동으로 만들어 줍니다!
@NoArgsConstructor
@AllArgsConstructor
public class ServerCResponse {

    private boolean success;
    private String message;
    private List<String> failedTraceIds = new ArrayList<>();

    // 💡 [에러 해결!] 실패한 건이 하나라도 있는지 확인해주는 편의 메서드
    public boolean hasFailures() {
        return failedTraceIds != null && !failedTraceIds.isEmpty();
    }
}