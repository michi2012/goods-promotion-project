# 체크리스트: 사용자 환불 PR③ — cs-bot·FAQ·프론트 (BL-029 마지막 PR)

- 마지막 업데이트: 2026-11-16

## [완료] BL-029 PR③ — BL-043 (PR #59)

### 진행 상황
- [x] 단계 1: cs-bot — serverA 환불 API 호출 + 상태는 serverB + Kafka 제거 (의존성 제거, 단계별 승인)
  - [x] 검증 통과 (`:cs-bot:build` — CsBotClient 6·CsBotTools 17·K8sDiscoveryConfig 5·CsChatController 2 통과, 컴파일로 Kafka 참조 0 확인)
  - 한계: cs-bot에는 `@SpringBootTest` 컨텍스트 로드 테스트가 없어 Kafka 제거 후 기동은 테스트로 확인 못함 (실제 기동은 LLM 키·Elasticsearch 필요). Kafka 자동 설정은 `spring-kafka`가 클래스패스에 있을 때만 켜지므로 코드상 영향 없음
- [x] 단계 2: FAQ 정책 맞춤
  - [x] 검증 통과 (YAML 파싱 OK — 4문항, 답변만 변경. 선착순 3일·미개봉·부분 환불 가능 문구 제거. `FaqDocumentLoader`는 기동마다 "카테고리+질문" UUID로 색인 → 질문이 그대로라 같은 ID로 덮어써져 옛 답변이 남지 않음)
- [x] 단계 3: 프론트 — 환불 버튼·상태 문구
  - [x] 검증 통과 (`npm run test` → 5개 파일·22개 통과(RefundForm 5·formatOrderStatus 2 신규) · `npx tsc --noEmit` exit 0 · `npm run lint` exit 0)
- [x] 단계 4: 화면 확인 (로컬 백엔드, Playwright 캡처)
  - 캡처: `.playwright-mcp/refund-1-paid.png`(PAID + 환불 폼) → `refund-2-requested.png`(확인 후 "환불 처리 중", 폼 사라짐) → `refund-3-refunded.png`(새로고침 "환불 완료") → `refund-4-rejected.png`(남의 로그인 ID → "주문을 찾을 수 없습니다", 주문은 PAID 유지)
  - 데이터: 재고 5 → 구매 3 → 환불 후 5, serverB REFUNDED. 콘솔 에러 1건은 거절(404) 응답 로그로 정상
  - [x] 사용자 확인 (2026-11-16)
- [x] 단계 5: 문서 (`refund_fatal` 설명, 명세 구현 단계 표를 백로그/PR 칸으로·포트폴리오 문구, 백로그 BL-043)
  - [x] 검증 통과 (grep — "수동 정산"·cs-bot 직접 발행 설명 0건, arch-snapshot 토픽 표에 `payment-cancelled`(.DLT) 추가, 명세 표 백로그/PR 칸·변경 이력, 프론트 `npm run build` exit 0)

### 최종 검증
- [x] cs-bot·프론트 테스트 전체 통과 (cs-bot 30, 프론트 22 + build)
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (compose·Helm Kafka 설정 미변경 → BL-044)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (25개: cs-bot 11·프론트 6·문서 8 — 리뷰 권장 크기(약 20)를 넘지만 문서 8개 포함)
- [x] (UI 변경) 사용자에게 화면·데이터 전후를 보여주고 확인받은 뒤 커밋 (e2e skill 7항)
- [x] 발견 사항을 백로그로 이전 (BL-044 cs-bot Kafka 흔적·기동 테스트)
- [x] (위험 변경 — 결제·환불 요청 경로) PR 생성 후 `/pr-review` 1회 — **사용자 결정으로 생략** (2026-11-16)
- [x] 포트폴리오 후보 판단 — 예 (BL-029 전체: 문제=분산 환경 환불의 이중 처리·유실·재고 불일치 / 원인=cs-bot 직접 발행·동시 요청 스냅샷·재시도 이중 복구·구매 표시 TTL / 대안 비교 다수 / 증거=E2E 동시 2회·재고 1회 복구·화면). 머지 후 한 줄 기록

### 발견 사항 (작업 중 별도 처리 필요한 것)
- compose·Helm의 cs-bot Kafka 설정 잔존, cs-bot 컨텍스트 로드 테스트 없음 → BL-044
