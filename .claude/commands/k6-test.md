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
- VU(가상 사용자 수)/duration을 사용자가 명시했으면 그 값을 쓰고, 명시하지 않았으면 스모크 기본값(5 VU, 30s)을 사용한다.

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

// vus/duration: Step 1에서 정한 값으로 변경 (기본 5 VU, 30s)
export const options = {
  vus: 5,
  duration: '30s',
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
- 요청 수: {http_reqs}
- 실패율: {http_req_failed}
- 응답 시간: avg {avg} / p95 {p95} / max {max}
- 체크 통과: {checks pass/fail}

⚠️ 이 결과는 로컬 스모크 수준입니다. SLO 비교용 정식 부하테스트는 staging/CI 환경에서 수행을 권장합니다.
```

Step 2에서 `kubectl port-forward`를 백그라운드로 띄웠다면 이 단계에서 해당 프로세스를 종료한다.
