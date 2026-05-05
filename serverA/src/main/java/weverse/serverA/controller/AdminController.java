package weverse.serverA.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import weverse.serverA.service.dlt.DeadLetterService;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final DeadLetterService deadLetterService;

    // 💡 관리자가 에러 테이블을 보고, 수동으로 재처리를 지시하는 API
    @PostMapping("/dlt/{dltId}/retry")
    public ResponseEntity<String> retryDlt(@PathVariable Long dltId) {
        deadLetterService.retryDeadLetter(dltId);
        return ResponseEntity.ok("DLT Re-processed Successfully");
    }
}