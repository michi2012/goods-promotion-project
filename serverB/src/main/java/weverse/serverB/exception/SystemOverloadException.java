package weverse.serverB.exception;

// 🚨 429 서버 혼잡 (Redis 큐 풀, In-flight 풀)
public class SystemOverloadException extends RuntimeException {
    public SystemOverloadException(String message) {
        super(message);
    }
}