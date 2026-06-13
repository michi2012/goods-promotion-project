package codebot.codebot.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class KubernetesTools {

    private final String namespace;

    public KubernetesTools(@Value("${k8s.namespace:promotion}") String namespace) {
        this.namespace = namespace;
    }

    @Tool(description = """
            K8s 클러스터의 Pod, Deployment 상태를 조회합니다 (읽기 전용).
            언제 호출: 코드 조사 중 특정 서비스의 파드 재시작·상태 이상이 코드 문제와 연관됐는지 확인이 필요할 때만 (필요시 조회).
            반환: Pod 목록(이름, 상태, 재시작 횟수), Deployment 목록(원하는/준비된 replica).
            실패 시: kubectl 실행 불가 환경이므로 스킵하고 코드 조사를 계속하세요.
            """)
    public String getClusterStatus() {
        try {
            String pods = runKubectl("get", "pods", "-n", namespace, "--no-headers");
            String deployments = runKubectl("get", "deployments", "-n", namespace, "--no-headers");
            log.info("[K8s] 클러스터 상태 조회 완료: namespace={}", namespace);
            return "[Pods]\n" + pods + "\n\n[Deployments]\n" + deployments;
        } catch (Exception e) {
            log.warn("[K8s] 클러스터 상태 조회 실패: {}", e.getMessage());
            return "K8s 클러스터 조회 실패 — kubectl 접근 불가 환경일 수 있습니다: " + e.getMessage();
        }
    }

    private String runKubectl(String... args) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("kubectl");
        command.addAll(List.of(args));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("kubectl 종료 코드 " + exitCode + ": " + output);
        }
        return output.isBlank() ? "(결과 없음)" : output;
    }
}
