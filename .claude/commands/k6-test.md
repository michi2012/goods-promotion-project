---
description: k6 부하테스트 스크립트 작성 및 로컬 스모크 실행. SLO 비교용 정식 성능 지표가 아닌 smoke-level 검증.
---

# /k6-test — k6 부하테스트 스크립트 작성 및 로컬 스모크 실행

API 엔드포인트에 대한 k6 부하테스트 스크립트를 작성하고, 로컬에서 스모크 수준으로 실행해 결과를 보고한다.

**이 커맨드의 역할 범위**: 스크립트 작성 + 로컬 스모크 실행(정상 동작/대략적인 응답 시간 확인)까지다.
SLO(p95/p99 등) 비교용 정식 부하테스트는 staging/CI 환경에서 수행해야 한다 — 로컬 환경의 리소스 스펙은 운영 환경과 다르므로 이 커맨드의 결과를 SLO 근거로 사용하지 않는다.

**사용법**
- `/k6-test {엔드포인트 설명 또는 docs/specs/*-design.md 경로} {BASE_URL}`
- `/k6-test {엔드포인트 설명 또는 경로}` — BASE_URL을 모르면 Step 2에서 확보한다

---

## Step 1. 대상 엔드포인트 파악

- 입력이 `docs/specs/*-design.md` 또는 `docs/arch-snapshot.md` 경로면 읽어서 엔드포인트(METHOD, 경로, 요청/응답 형태)를 파악한다.
- 대화 중 설명으로 주어졌으면 그 내용을 사용한다.
- 엔드포인트를 특정할 수 없으면 사용자에게 묻는다.
- VU(가상 사용자 수)/duration을 사용자가 명시했으면 그 값을 쓴다.
- 명시하지 않았으면 다음 우선순위로 **시작 VU**를 추정한다 (duration은 단계별 20s 기본, Step 3 참고):
  1. `docs/arch-snapshot.md`, `docs/system-design.md`, 모니터링/알림 설정(SRE 대시보드 등)에 해당 엔드포인트의 예상 트래픽(RPS)이나 SLO(p95 임계값 등)가 명시되어 있으면 그 값을 기준으로 삼는다.
  2. 위 문서에 없으면 사용자에게 예상 피크 트래픽(RPS)을 알고 있는지 묻는다.
  3. 둘 다 없으면 아래 두 가지를 확인해 추정한다:
     - **서비스 리소스**: 대상 서비스의 K8s 리소스 스펙(`helm/*/values.yaml`의 `resources.requests/limits`) 또는 로컬 docker-compose 리소스 제한을 확인한다.
     - **로직 흐름**: 대상 엔드포인트의 컨트롤러/서비스 코드를 읽어 처리 흐름을 파악한다 (캐시/Redis 단일 조회 vs DB 쿼리·조인 개수 vs 외부 API 호출 포함 여부).
     - 두 정보를 조합해 시작 VU를 정한다 — 예: 리소스가 넉넉하고 캐시 단일 조회면 높게(예: 10), 리소스가 제한적이거나 DB 조회/외부 호출이 섞여 있으면 낮게(예: 3~5) 잡는다. 이 값은 추정치이며, Step 3의 단계적 증가로 실제 한계를 탐색하는 것이 목적임을 명시한다.

---

## Step 2. BASE_URL 확보

BASE_URL이 인자로 주어졌으면 그대로 사용하고 Step 3으로 진행한다. 없으면 사용자에게 환경을 묻는다.

- **EC2/직접 접근 가능한 서버**: 사용자가 보안그룹에서 자신의 IP→대상 포트를 직접 허용해야 한다 (AWS 인프라 변경이므로 Claude가 직접 수행하지 않는다). 허용 후 `http://{IP}:{port}` 형태의 BASE_URL을 안내받는다.
- **Kubernetes**: 대상 Service 이름·네임스페이스·포트를 받아 다음을 `run_in_background: true`로 실행한다.

```powershell
kubectl port-forward -n {namespace} svc/{service} {local-port}:{remote-port}
```

  - BASE_URL은 `http://localhost:{local-port}`로 설정한다.
  - Step 5 종료 시 이 포트포워드 프로세스를 정리한다.

---

## Step 3. k6 스크립트 작성

`k6/{대상명}.js`에 저장한다. `k6/` 디렉토리가 없으면 함께 생성한다.

BASE_URL은 하드코딩하지 않고 환경변수(`__ENV.BASE_URL`)로 받는다 — 같은 스크립트를 EC2/K8s/로컬 어디서든 재사용할 수 있게 한다.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

// stages: Step 1에서 추정한 시작 VU를 기준으로 배수 증가 (예: 시작 VU=5 → 5 → 10 → 20)
// 너무 작은 폭으로 증가시키면 한계 지점을 못 찾으므로, 단계마다 2배 수준으로 늘린다.
export const options = {
  stages: [
    { duration: '20s', target: 5 },  // 1단계: 추정 시작 VU
    { duration: '20s', target: 10 }, // 2단계: 2배
    { duration: '20s', target: 20 }, // 3단계: 4배 — 한계 탐색
  ],
};

export default function () {
  // method/경로/요청 바디: 대상 엔드포인트에 맞게 변경 (예: http.post(url, JSON.stringify(body)))
  const res = http.get(`${__ENV.BASE_URL}/example/path`);

  // 기대 상태 코드: 대상 엔드포인트에 맞게 변경
  check(res, {
    'status is 200': (r) => r.status === 200,
  });

  sleep(1);
}
```

- 명세서에 여러 엔드포인트가 있으면 시나리오별로 함수를 나누거나 `scenarios` 옵션으로 구성한다.
- thresholds(`http_req_duration` 등)는 참고용으로만 추가하고, 주석으로 "SLO 기준 아님 — 로컬 스모크용 참고값" 명시한다.

---

## Step 4. 로컬 스모크 실행

k6 설치 여부를 먼저 확인한다.

```powershell
k6 version
```

설치되어 있지 않으면 설치 방법(`winget install k6.k6` 또는 `choco install k6`)을 안내하고 중단한다.

설치되어 있으면 `run_in_background: true`로 실행한다.

```powershell
k6 run -e BASE_URL={BASE_URL} k6/{대상명}.js
```

---

## Step 5. 결과 보고 및 정리

실행 완료 알림을 받으면 k6 요약 출력에서 다음을 추출해 보고한다.

```
## k6 스모크 결과: {대상명}

- 대상: {METHOD} {경로} (BASE_URL: {BASE_URL})
- stages: {1단계 VU} → {2단계 VU} → {3단계 VU} (각 {duration})
- 요청 수: {http_reqs}
- 실패율: {http_req_failed}
- 응답 시간: avg {avg} / p95 {p95} / max {max}
- 체크 통과: {checks pass/fail}
- 안정 구간: {실패율/응답시간이 급격히 나빠지기 시작한 단계 — 그 이전 단계 VU를 다음 라운드(staging) 시작 기준으로 활용 가능}

⚠️ 이 결과는 로컬 스모크 수준입니다. SLO 비교용 정식 부하테스트는 staging/CI 환경에서 수행을 권장합니다.
```

Step 2에서 `kubectl port-forward`를 백그라운드로 띄웠다면 이 단계에서 해당 프로세스를 종료한다.
