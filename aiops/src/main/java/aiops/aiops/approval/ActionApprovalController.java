package aiops.aiops.approval;

import lombok.RequiredArgsConstructor;
import aiops.aiops.approval.ActionApprovalService.PendingAction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/action")
@RequiredArgsConstructor
public class ActionApprovalController {

    private final ActionApprovalService approvalService;

    @PostMapping("/approve/{id}")
    public ResponseEntity<String> approve(@PathVariable String id) {
        Optional<PendingAction> action = approvalService.approve(id);
        if (action.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        PendingAction a = action.get();
        return ResponseEntity.ok("승인 완료 [" + a.id() + "]: " + a.actionType() + " — " + a.reason());
    }
}
