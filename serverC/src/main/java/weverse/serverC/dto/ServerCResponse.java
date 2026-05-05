package weverse.serverC.dto;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ServerCResponse {

    private boolean success;
    private String message;
    private List<String> failedTraceIds = new ArrayList<>();

    public boolean hasFailures() {
        return failedTraceIds != null && !failedTraceIds.isEmpty();
    }
}