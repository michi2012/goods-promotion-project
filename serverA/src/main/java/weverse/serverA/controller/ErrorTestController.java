package weverse.serverA.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import weverse.serverA.exception.PromotionException;

@RestController
@RequestMapping("/api/v1/test/error")
public class ErrorTestController {

    @GetMapping("/promotion")
    public void triggerPromotionException() {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "강제 500 에러 발생!");
    }
}
