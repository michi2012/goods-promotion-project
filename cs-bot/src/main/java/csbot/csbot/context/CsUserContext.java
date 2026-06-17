package csbot.csbot.context;

import csbot.csbot.classification.CsClassification;
import csbot.csbot.client.CsBotClient;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
@RequiredArgsConstructor
public class CsUserContext {

    private final CsBotClient csBotClient;

    @Getter
    @Setter
    private String loginId;

    @Getter
    @Setter
    private CsClassification.Urgency urgency = CsClassification.Urgency.MEDIUM;

    private Long numericId;

    public Long resolveNumericId() {
        if (numericId == null) {
            numericId = csBotClient.resolveNumericUserId(loginId);
        }
        return numericId;
    }
}
