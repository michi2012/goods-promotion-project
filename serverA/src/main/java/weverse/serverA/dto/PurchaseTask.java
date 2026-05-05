package weverse.serverA.dto;

import org.springframework.http.ResponseEntity;

import java.util.concurrent.CompletableFuture;

public record PurchaseTask(
        PurchaseMessage message,
        CompletableFuture<ResponseEntity<String>> future
) {}
