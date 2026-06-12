# 맥락 노트: 프론트엔드 통합 기반 작업

## 왜 이 방식을 선택했는가
기존 promotion 프로젝트는 Spring Boot 멀티모듈 백엔드(serverA/B/C, gateway, discovery, user-service, aiops)로 구성되어 있고, "기획(spec-draft/spec-to-tickets) → 개발 → 테스트"가 CLAUDE.md 규칙(테스트 동시 작성, 커밋 단위 원칙 등)으로 한 흐름에 묶여 운영되고 있다. 사용자가 1인 풀스택 운영을 목표로 프론트엔드를 추가하고 싶어했고, 대화를 통해 다음 결론에 도달했다:

- "PM/디자이너/프론트엔드 별도 팀 + 중앙 관리자" 구조는 채택하지 않는다 — 작업이 레이어를 가로지르므로 핸드오프 비용만 늘어난다. 대신 기존 한 사이클을 양 끝(기획 앞, 프론트 뒤)으로 넓힌다.
- "디자인"은 별도 역할/단계가 아니라 **컴포넌트 라이브러리(shadcn/ui) 적용**으로 프론트 구현에 흡수한다 — 클로드는 시각적 디자인 창작을 할 수 없으므로, 이미 디자이너가 검증해 놓은 시스템을 쓰는 게 맞다.
- 프론트-백엔드를 "한 사이클"로 만드는 핵심은 **연동 규약의 이중 안전망**이다: (a) 수동 문서로 API 계약의 의미·동작을 명시하고, (b) springdoc-openapi + orval로 타입/훅을 자동 생성해 모양(shape) 불일치를 빌드 단계에서 차단한다.
- 검증 대상 서버는 serverB — `OrderQueryController`가 순수 조회(Query) 전용이고 응답이 단순(`OrderStatusResponse`, `Long`)해서 파이프라인 전체를 검증하기에 가장 적합하다 (serverA는 GET 엔드포인트가 없고, serverC는 결제 데이터라 검증용 노출에 부적합).

## 검토했으나 채택하지 않은 대안

### 대안 A: openapi-typescript (타입만 생성)
- 무엇: 순수 TS 타입만 자동 생성, API 호출 함수는 직접 작성
- 왜 안 썼나: 이미 TanStack Query를 채택하기로 한 상태에서, orval은 쿼리 훅까지 자동 생성해 1인 개발자의 진짜 병목(API 호출 보일러플레이트 작성 시간)을 줄여준다. 가벼운 시작보다 leverage가 큰 쪽을 선택했다.

### 대안 B: 게이트웨이에 OpenAPI 스펙 통합 (aggregation)
- 무엇: gateway-service에서 여러 서비스의 스펙을 한 문서로 모아 노출
- 왜 안 썼나: 여러 팀의 API를 한 문서로 통합 제공하기 위한 기능인데, 1인 개발에는 불필요한 복잡도다. 프론트가 실제로 호출하는 계약은 각 서비스 레벨에 있으므로, 타입 생성도 서비스 단위로 하는 게 맞다.

### 대안 C: 별도 레포로 프론트엔드 분리
- 무엇: frontend를 promotion-project와 별개의 레포로 관리
- 왜 안 썼나: "API 계약 변경 ↔ 프론트 반영"을 한 커밋/PR 흐름으로 추적해야 "한 사이클" 목표가 성립한다. 별도 레포는 1인 운영에 동기화 오버헤드만 늘린다.

### 대안 D: Figma MCP로 클로드가 직접 디자인
- 무엇: Figma MCP를 이용해 클로드가 디자인을 새로 생성하게 하는 방안
- 왜 안 썼나: Figma MCP는 기존 디자인을 코드로 변환하는 "읽기" 용도지 "그리기" 용도가 아니며, 클로드는 시각적 미적 판단 능력이 본질적으로 부족해 컴포넌트 라이브러리보다 못한 결과를 낳는다.

## 기존 코드베이스 컨벤션
- 멀티모듈 Gradle 구조: `settings.gradle`에 serverA/B/C, aiops, gateway-service, discovery-service, user-service 포함
- 컨트롤러 패턴: `@RestController` + `@RequestMapping`, 응답은 `ResponseEntity<DTO>`로 래핑 (예: `serverB/.../controller/OrderQueryController.java`)
- 빌드: `gradlew.bat`이 루트에 위치, Windows+PowerShell 환경, bash 명령 사용 금지
- 커밋 단위: 전체 구현 → 테스트 통과 → 기능별 커밋 (중간 커밋 금지)
- 테스트 분업: 코드 작성은 Claude, `gradlew test`/`npm test` 등 실행은 사용자

## 관련 파일/위치
- `serverB/src/main/java/promotion/serverB/controller/OrderQueryController.java` — 검증 대상 API (`GET /api/v1/orders/{orderId}/status`)
- `serverB/src/main/java/promotion/serverB/dto/OrderStatusResponse.java` — 검증 대상 응답 DTO
- `serverB/build.gradle` — springdoc-openapi 의존성 추가 위치
- `.claude/skills/jpa/SKILL.md`, `.claude/skills/testing/SKILL.md` — 새 frontend/frontend-testing skill 작성 시 참고할 기존 패턴

## 외부 참조
- shadcn/ui: https://ui.shadcn.com
- orval: https://orval.dev
- springdoc-openapi: https://springdoc.org

---

## [진행 중] 디자인 다듬기 루프 (브라우저 MCP 기반 시각 피드백)

### 왜 이 방식을 선택했는가
프론트엔드 통합 기반 작업의 plan.md에서 "디자인 다듬기 루프"를 의도적으로 비범위로 분류했었다 — "파이프라인이 갖춰진 후 별도 작업"으로 미뤄둔 것. 파이프라인(화면 구현 + 테스트)이 완료된 지금, 사용자가 이를 별도 작업으로 진행하자고 요청했다.

사용자는 프론트엔드 디자인 개념에 확신이 없다고 밝혔고("나도 확신없어. 난 프론트 쪽 개념이 많이없어서"), Claude에게 추천을 요청했다. 이에 따라 다음 방향을 추천했고 승인받았다:
- **MCP 선택**: Playwright MCP — 이미 `frontend-testing`에서 Playwright를 채택했으므로 도구 생태계가 일관되고, 공식 지원되는 MCP 서버라 안정성이 높다.
- **루프 범위**: 스크린샷 확인뿐 아니라 코드 수정까지 포함하는 전체 루프 — "스크린샷 → 설명 → 사용자가 평범한 말로 피드백 → Claude가 코드 수정 → 재확인". 1인 개발 환경에서 디자인 어휘를 몰라도 "이 버튼이 너무 커 보여요" 같은 직관적 피드백만으로 개선이 가능하도록 한다.
- **저장 위치**: 프로젝트 전용 skill (`.claude/skills/`) — 이번이 첫 시도라 절차가 다듬어질 여지가 많고, 검증되지 않은 가정을 일반화된 문서에 박아두지 않기 위함.

### 검토했으나 채택하지 않은 대안

#### 대안 A: Chrome DevTools MCP
- 무엇: Chrome DevTools 프로토콜 기반 MCP 서버로 브라우저 제어/스크린샷
- 왜 안 썼나: 이미 Playwright를 테스트 스택으로 채택했으므로, 같은 도구 생태계를 MCP에서도 쓰는 것이 도구 중복을 피하고 학습/유지보수 비용을 줄인다.

#### 대안 B: 스크린샷만 확인하고 코드 수정은 별도 요청으로 분리
- 무엇: Claude가 스크린샷으로 현재 상태만 보여주고, 수정은 사용자가 별도로 다시 요청해야 하는 좁은 루프
- 왜 안 썼나: 핸드오프 단계가 늘어나 1인 개발의 효율을 떨어뜨린다. 사용자가 디자인 어휘에 익숙하지 않으므로, "평범한 말 → 코드 수정"까지 Claude가 한 번에 연결하는 편이 마찰이 적다.

#### 대안 C: 범용 skill로 일반화해서 문서화
- 무엇: 다른 프로젝트에서도 쓸 수 있도록 일반화된 디자인 피드백 루프 skill 작성
- 왜 안 썼나: 첫 시도이고 절차가 검증되지 않았다. 일반화는 실제로 여러 차례 사용해 패턴이 안정된 후에 하는 것이 맞다 (사용자가 이전 대화에서 밝힌 "프로젝트별로 적응시킨다"는 철학과도 일치).

### 기존 코드베이스 컨벤션
- MCP 서버는 `.mcp.json`에 등록하고 `.claude/settings.json`의 `enabledMcpServers`로 활성화 (예: `codex`, `mysql`, `linear`)
- skill 작성 패턴: frontmatter(`name`/`description`) + 트리거 조건 명시 + "자가 점검" 섹션 — `frontend`, `frontend-testing` SKILL.md 참고
- 프론트 화면 검증은 Playwright로 수행 (`frontend-testing` skill 3절)

### 관련 파일/위치
- `.mcp.json` — MCP 서버 등록 위치 (⚠️ `OPENAI_API_KEY` 평문 포함 — 열람/수정 시 노출 주의, 절대 값을 출력하지 않을 것)
- `.claude/settings.json` — `enabledMcpServers` 활성화 목록
- `frontend/src/pages/OrderStatusPage.tsx` — 루프 시연 대상 화면
- `.claude/skills/frontend/SKILL.md`, `.claude/skills/frontend-testing/SKILL.md` — 새 skill이 참조/연계할 기존 문서

### 외부 참조
- Playwright MCP: https://github.com/microsoft/playwright-mcp
