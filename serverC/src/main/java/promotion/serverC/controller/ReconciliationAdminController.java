package promotion.serverC.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import promotion.serverC.dto.ReconciliationRunResponse;
import promotion.serverC.exception.AdminForbiddenException;
import promotion.serverC.service.ReconciliationRunService;

import java.time.LocalDate;

/**
 * PG 대사 수동 실행 관리자 API (docs/specs/pg-reconciliation-batch.md "API").
 * 실행은 비동기 — 202로 실행 ID만 돌려주고, 결과는 메트릭·알람·reconciliation_mismatches로 확인한다.
 * X-User-Role은 게이트웨이가 JWT를 검증해 넣는다 (PaymentAdminController와 같은 보호 방식).
 */
@RestController
@RequestMapping("/api/v1/payments/admin/reconciliations")
@RequiredArgsConstructor
public class ReconciliationAdminController {

    private static final String USER_ROLE_HEADER = "X-User-Role";
    private static final String ADMIN_ROLE = "ROLE_ADMIN";

    private final ReconciliationRunService runService;

    @PostMapping
    public ResponseEntity<ReconciliationRunResponse> run(
            @RequestHeader(value = USER_ROLE_HEADER, required = false) String role,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (!ADMIN_ROLE.equals(role)) {
            throw new AdminForbiddenException();
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                             .body(ReconciliationRunResponse.from(runService.runManually(date)));
    }
}
