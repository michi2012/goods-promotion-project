package weverse.serverA.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import weverse.serverA.dto.request.CompensationRequest;
import weverse.serverA.service.CompensationService;

import java.util.List;

@RestController
@RequestMapping("/api/v1/internal")
@RequiredArgsConstructor
public class InternalController {

    private final CompensationService compensationService;

    // 어디서 터지든 이 API를 찌르면 재고를 롤백해줍니다.
    @PostMapping("/compensate")
    public ResponseEntity<String> triggerCompensation(@RequestBody List<CompensationRequest> requests) {
        compensationService.compensate(requests);
        return ResponseEntity.ok("Compensation Processed");
    }
}