package promotion.serverC.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import promotion.serverC.exception.InvalidReconciliationDateException;
import promotion.serverC.exception.ReconciliationAlreadyRunningException;
import promotion.serverC.service.ReconciliationRunService;
import promotion.serverC.service.ReconciliationRunService.Mode;
import promotion.serverC.service.ReconciliationRunService.Run;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReconciliationAdminController.class)
class ReconciliationAdminControllerTest {

    private static final String BASE = "/api/v1/payments/admin/reconciliations";
    private static final LocalDate D = LocalDate.of(2026, 11, 16);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReconciliationRunService runService;

    @Test
    @DisplayName("관리자 실행 → 202 + 실행 ID·모드")
    void runAsAdmin() throws Exception {
        when(runService.runManually(D)).thenReturn(new Run(7L, Mode.RESTART));

        mockMvc.perform(post(BASE).param("date", "2026-11-16").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobExecutionId").value(7))
                .andExpect(jsonPath("$.mode").value("RESTART"));
    }

    @Test
    @DisplayName("관리자가 아니면 403, 실행하지 않는다")
    void forbiddenForNonAdmin() throws Exception {
        mockMvc.perform(post(BASE).param("date", "2026-11-16"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(BASE).param("date", "2026-11-16").header("X-User-Role", "ROLE_USER"))
                .andExpect(status().isForbidden());

        verify(runService, never()).runManually(any());
    }

    @Test
    @DisplayName("오늘 이후 날짜 → 400")
    void invalidDate() throws Exception {
        when(runService.runManually(D)).thenThrow(new InvalidReconciliationDateException(D));

        mockMvc.perform(post(BASE).param("date", "2026-11-16").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("date 누락·형식 오류 → 400 (500 아님)")
    void missingOrMalformedDate() throws Exception {
        mockMvc.perform(post(BASE).header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(BASE).param("date", "2026-13-01").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("같은 날짜 실행 중 → 409")
    void alreadyRunning() throws Exception {
        when(runService.runManually(D)).thenThrow(new ReconciliationAlreadyRunningException(D));

        mockMvc.perform(post(BASE).param("date", "2026-11-16").header("X-User-Role", "ROLE_ADMIN"))
                .andExpect(status().isConflict());
    }
}
