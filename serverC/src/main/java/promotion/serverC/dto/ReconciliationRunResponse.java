package promotion.serverC.dto;

import promotion.serverC.service.ReconciliationRunService;

public record ReconciliationRunResponse(
        long jobExecutionId,
        ReconciliationRunService.Mode mode
) {
    public static ReconciliationRunResponse from(ReconciliationRunService.Run run) {
        return new ReconciliationRunResponse(run.jobExecutionId(), run.mode());
    }
}
