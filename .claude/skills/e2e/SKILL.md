---
name: e2e
description: E2E(종단 간) 테스트를 설계·작성·실행·보고할 때 활성화. API E2E(서버 간 흐름), UI E2E(Playwright), 사전 점검, 배포 후 스모크 테스트 포함. 사용자가 E2E, 전체 흐름 검증, 시나리오 테스트, 스모크, 사전 점검(preflight)을 언급하거나 e2e/ 폴더 작업 시 발동. 단위·슬라이스 테스트(testing, frontend-testing skill)에서는 발동하지 않음.
---

# E2E 테스트 원칙 (범용)

세 가지를 지킨다.
1. **최종 결과까지 확인한다.** "화면이 넘어갔다", "200이 왔다"는 E2E 통과가 아니다. 사용자가 기대하는 최종 상태(처리 완료, 저장된 값)를 확인해야 통과다.
2. **시나리오는 저장소 스크립트로 고정한다.** 즉석 명령은 디버깅용이며 검증 근거로 쓰지 않는다.
3. **근거와 함께 보고한다.** 실행한 명령, 결과, 핵심 화면, 핵심 데이터의 전후 비교.

프로젝트별 적용 값(스크립트 위치, 시나리오, 점검 대상, 실행 시점, 배포 스위치)은 **같은 폴더의 `project.md`를 함께 읽는다.** 이 skill을 다른 프로젝트로 옮길 때는 이 파일은 그대로 두고 `project.md`만 새로 쓴다. UI 테스트 세부(시각 회귀, 접근성 axe)는 `frontend-testing` skill을 따른다.

## 1. 층 구분

| 층 | 검증 대상 | 도구 예 |
|---|---|---|
| API E2E | 여러 서버·메시지·저장소를 거친 최종 상태 | Playwright `request`, 테스트 런타임의 HTTP 클라이언트 |
| UI E2E | 핵심 사용자 흐름(P0)만. 화면 이동 + 최종 결과 표시 | Playwright |
| 배포 후 스모크 | 로컬에서 검증할 수 없는 배포 환경 고유 부분 | API E2E 시나리오 재사용(대상 주소만 변경) |

- 서버 간 비동기 흐름은 UI E2E로 검증하지 않는다. API E2E가 담당한다.
- UI E2E는 개수를 적게 유지한다. 흐름마다 다 만들면 유지 비용이 커진다.

## 2. 블랙박스 원칙: API로 최종 결과를 확인한다

- 저장소(DB 등)를 직접 조회하지 않고 **시스템이 노출한 API로 상태를 확인**한다. 그래야 저장소 종류·접속 정보에 묶이지 않고, 다른 프로젝트에서도 같은 패턴을 쓸 수 있다.
- 확인할 조회 API가 없으면 테스트 전용 엔드포인트를 임의로 만들지 말고 사용자와 먼저 상의한다. 저장소 직접 조회는 실패 원인 진단 용도로만 쓴다.
- 비동기 결과는 **타임아웃이 있는 폴링**으로 기다린다. 고정 대기(sleep) 금지.
- 테스트 데이터는 **테스트가 직접 만든다**(실행마다 고유한 이름·식별자). 미리 만들어둔 데이터에 의존하지 않는다.

## 3. Playwright 패턴

API E2E: 생성 → 비동기 처리 → 최종 상태 폴링

```ts
// e2e/api/<flow>.spec.ts
import { test, expect } from '@playwright/test'

const API = process.env.E2E_API_BASE_URL ?? 'http://localhost:8080'

test('요청 생성 → 비동기 처리 → 최종 상태 확인', async ({ request }) => {
  const resource = await request.post(`${API}/api/v1/resources`, {
    data: { name: `e2e-${Date.now()}`, price: 1000 },
  })
  expect(resource.ok()).toBeTruthy()
  const { id } = await resource.json()

  const submitted = await request.post(`${API}/api/v1/requests`, { data: { resourceId: id, quantity: 2 } })
  const { requestId } = await submitted.json()

  // 최종 상태와 값까지 확인한다 (상태만 보고 끝내지 않는다)
  await expect
    .poll(async () => (await (await request.get(`${API}/api/v1/requests/${requestId}`)).json()), {
      timeout: 30_000,
      intervals: [1_000, 2_000, 5_000],
    })
    .toMatchObject({ status: 'DONE', amount: 2000 })
})
```

UI E2E: 화면 이동이 아니라 최종 결과 표시까지 확인

```ts
await page.getByRole('button', { name: '제출' }).click()
await expect(page).toHaveURL(/\/requests\/.+/)
await expect(page.getByText('DONE')).toBeVisible({ timeout: 30_000 }) // 처리 완료까지
```

- 대상 주소·타임아웃은 환경변수로 받는다(로컬·CI·배포 환경이 같은 스크립트를 쓴다).

## 4. 사전 점검 (preflight)

- E2E 전에 필요한 것들이 준비됐는지 먼저 확인한다: 필요한 프로세스·컨테이너, 각 앱의 health 엔드포인트, 메시지 파이프라인(커넥터·컨슈머 등)의 상태.
- 점검도 스크립트로 고정한다(예: Playwright `globalSetup`이나 같은 런타임의 별도 스크립트).
- **점검이 실패하면 E2E를 돌리지 않는다.** 환경 문제로 보고하고, 환경 문제를 코드 수정으로 해결하려 하지 않는다.

## 5. 실행 시점

| 시점 | 실행 범위 |
|---|---|
| 로컬 (커밋 전) | 바꾼 부분의 테스트. 흐름을 건드렸으면 해당 API E2E, 화면을 건드렸으면 UI E2E까지 |
| CI – PR마다 | 빠르고 결과가 매번 같은 테스트만 (단위·통합·계약, UI E2E는 mock 기준) |
| CI – 머지 후 / 야간 / 수동 | 전체 스택 E2E (API + UI) |
| 배포 후 | 스모크. 배포가 실제로 일어났을 때만 실행하고, 배포가 없으면 skipped |

## 6. 셸 독립성

- 로컬과 CI가 공유하는 E2E·점검 스크립트는 특정 OS 셸(PowerShell, bash)에 의존하지 않게, 프로젝트가 이미 쓰는 런타임(Node, JVM 등)으로 작성한다.
- 환경마다 다른 값은 환경변수로 받는다.

## 7. 결과 보고

E2E를 돌렸으면 보고에 다음을 포함한다.
- 실행한 명령과 결과 (통과·실패 개수)
- 핵심 화면 스크린샷 (UI E2E)
- 핵심 데이터의 전후 비교 (어떤 값이 어떤 상태로 바뀌었는지)
- 실패 시: trace·리포트 경로, 마지막으로 관측한 상태

**새 기능이나 UI 변경이면 사용자에게 화면과 데이터를 보여주고 확인을 받은 뒤 커밋한다.**

## 8. 자가 점검
- [ ] 최종 상태와 값까지 확인하는가? (화면 이동·200 응답만 보고 끝내지 않았는가)
- [ ] 시나리오가 저장소에 커밋된 스크립트인가?
- [ ] 비동기 결과를 타임아웃 있는 폴링으로 기다리는가? (고정 sleep 없음)
- [ ] 테스트가 자기 데이터를 직접 만드는가?
- [ ] 사전 점검을 먼저 통과했는가?
- [ ] 스크립트가 특정 셸에 의존하지 않고, 환경 값은 환경변수인가?
- [ ] 보고에 명령·결과·스크린샷·데이터 전후를 포함했는가?
