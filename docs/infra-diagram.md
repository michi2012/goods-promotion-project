# Infrastructure Diagram
_생성일: 2026-05-29 / 업데이트: 2026-06-06 (Istio Ambient 레이어 추가)_

## 1. 서비스 토폴로지 (local — Docker Compose)

```mermaid
flowchart TD
    Client(["Client"])

    subgraph MSA["MSA Layer (docker-compose.msa.yml)"]
        GW["gateway-service\n:8088"]
        DS["discovery-service\n:8761"]
    end

    subgraph App["Application (docker run)"]
        A["server-a\n:8080"]
        B["server-b\n:8081"]
        C["server-c\n:8082"]
        AIO["aiops\n:8085"]
    end

    subgraph Infra["Core Infrastructure (docker-compose.infra.yml)"]
        MySQL["mysql\n:3307"]
        MySQLC["mysql-c\n:3308"]
        Redis["redis\n:6379"]
        RedisB["redis-b\n:6380"]
        Kafka["kafka\n:9092"]
        KC["kafka-connect\n:8083"]
        RP["redpanda-console\n:9080"]
    end

    PG(["PG사 외부 API"])

    %% 외부 트래픽
    Client -->|"HTTP :8088"| GW

    %% 게이트웨이 → 서비스 (Eureka lb://, Rate Limiting 적용)
    GW -->|"lb://serverA"| A
    GW -->|"lb://serverB"| B
    GW -->|"lb://serverC"| C
    GW -->|"lb://aiops"| AIO

    %% Eureka 등록
    GW -->|"register + fetch"| DS
    A -->|"register"| DS
    B -->|"register"| DS
    C -->|"register"| DS
    AIO -->|"register"| DS

    %% 앱 → 인프라
    A -->|"JPA"| MySQL
    A -->|"Lettuce"| Redis
    A -->|"consume"| Kafka
    B -->|"Lettuce"| RedisB
    B -->|"produce/consume"| Kafka
    C -->|"JPA"| MySQLC
    C -->|"consume"| Kafka

    %% Debezium CDC
    MySQL -->|"binlog"| KC
    MySQLC -->|"binlog"| KC
    KC -->|"CDC 이벤트"| Kafka

    %% 외부 결제
    C -->|"Circuit Breaker"| PG

    %% Kafka UI
    Kafka -.->|"모니터링"| RP
```

---

## 2. 서비스 토폴로지 (K8s — EKS)

```mermaid
flowchart TD
    Internet(["Internet"])
    CF(["Cloudflare\nWAF + DDoS"])

    subgraph AWS["AWS"]
        ALB["ALB\nHTTPS→HTTP"]
    end

    subgraph K8s["Kubernetes (promotion namespace)"]
        ING["K8s Ingress\n(AWS ALB)"]
        GW["gateway-service\n:8088"]

        subgraph App["Application Pods"]
            A["server-a\n:8080"]
            B["server-b\n:8081"]
            C["server-c\n:8082"]
            AIO["aiops\n:8085"]
        end

        subgraph Infra["Infrastructure Pods"]
            KF["kafka\nStatefulSet+PVC"]
            KC["kafka-connect\nDebezium"]
        end
    end

    subgraph AWSManaged["AWS Managed Services"]
        RDS_A["RDS\norder DB"]
        RDS_C["RDS\npayment DB"]
        EC_A["ElastiCache\nredis-a"]
        EC_B["ElastiCache\nredis-b"]
    end

    PG(["PG사 외부 API"])

    Internet --> CF --> ALB --> ING --> GW
    GW -->|"http://server-a:8080"| A
    GW -->|"http://server-b:8081"| B
    GW -->|"http://server-c:8082"| C
    GW -->|"http://aiops:8085"| AIO

    A -->|"JPA"| RDS_A
    A -->|"Lettuce"| EC_A
    A -->|"produce/consume"| KF
    B -->|"Lettuce"| EC_B
    B -->|"produce/consume"| KF
    C -->|"JPA"| RDS_C
    C -->|"produce/consume"| KF
    GW -->|"Lettuce"| EC_A

    RDS_A -->|"binlog"| KC
    RDS_C -->|"binlog"| KC
    KC -->|"CDC 이벤트"| KF

    C -->|"Circuit Breaker"| PG
```

> K8s 환경에서는 Eureka(discovery-service) 미배포. AIOps는 K8s API Server에 RBAC로 접근해 HPA 조정·Helm 롤백·롤링 재시작·Istio VirtualService 트래픽 시프트·DestinationRule Outlier Detection 조정을 Slack 승인 후 실행한다. `SPRING_PROFILES_ACTIVE=k8s`로 K8s Service DNS 직접 사용.
> Istio Ambient: 사이드카 없이 ztunnel(L4 DaemonSet) + waypoint proxy(L7)로 구성. waypoint가 VirtualService 가중치 라우팅과 DestinationRule Outlier Detection을 적용한다. ztunnel/waypoint는 애플리케이션에 투명한 레이어이므로 다이어그램 라우팅에는 표현하지 않음.

---

## 3. 옵저버빌리티 파이프라인

```mermaid
flowchart LR
    subgraph Src["Signal Sources"]
        SA["server-a :8080"]
        SB["server-b :8081"]
        SC["server-c :8082"]
        GW["gateway-service :8088"]
        DS["discovery-service :8761"]
        AIO["aiops :8085"]
        Logs["/logs/*.log\n(shared-logs vol)"]
        Infra["redis/kafka/mysql\nexporters"]
    end

    subgraph Collect["Collection"]
        OTEL["otel-collector\n:4318(http) :4317(grpc)"]
        VEC["vector"]
        PROM["prometheus\n:9090"]
    end

    subgraph Store["Storage"]
        Tempo["tempo\n:3200"]
        Loki["loki\n:3100"]
    end

    subgraph Vis["Visualization & Alerting"]
        Grafana["grafana\n:3000"]
        AM["alertmanager\n:9093"]
    end

    %% Traces: 앱 → otel-collector → tempo
    SA & SB & SC & GW & DS & AIO -->|"OTLP"| OTEL
    OTEL -->|"OTLP/gRPC"| Tempo

    %% Logs: local=shared-logs 파일, k8s=stdout→/var/log/pods/ DaemonSet
    Logs -.->|"file tail"| VEC
    VEC --> Loki

    %% Metrics: prometheus scrape
    SA & SB & SC & GW & DS & AIO -->|"scrape"| PROM
    Infra -->|"exporter metrics"| PROM

    %% Grafana datasources
    PROM --> Grafana
    Tempo --> Grafana
    Loki --> Grafana
    AM --> Grafana

    %% Alerting
    PROM -->|"alert rules"| AM
    AM -->|"webhook"| AIO
```

> 점선(`-.->`)은 간접 연결을 나타냄. local: shared-logs 볼륨 경유 / K8s: Vector DaemonSet이 노드 `/var/log/pods/` hostPath 마운트.
