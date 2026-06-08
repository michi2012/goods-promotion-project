# 맥락 노트: v1.7.0 변경 내용을 README.md / docs/port.md에 반영

## 왜 이 방식을 선택했는가
- v1.7.0에서 두 가지 축의 변경이 있었다: (1) 결제 종단 간 p95 지연 모니터링/알림(`PaymentE2ELatencyHigh`, SRE 대시보드 신규 패널), (2) Linear MCP 워크플로우 통합(`pr`/`spec-draft`/`spec-to-tickets` 신규 명령어 + `plan`/`incident`/`release-notes`의 Linear 연동). 두 축 모두 기존 README.md/docs/port.md의 설명이 v1.6.x 시점에 머물러 있어 최신화가 필요했다.
- README.md는 "기술 프로젝트 README" 성격(시스템 아키텍처·설계 결정·성능 개선 중심)이고, AI 개발 워크플로우 섹션은 docs/port.md에만 존재한다. 사용자에게 "README에도 AI 워크플로우 섹션을 신설할지" 확인했고, **port.md 전용으로 유지하기로 결정**했다 — README에 욱여넣으면 중복·비대화만 생기고, 두 문서의 성격 구분이 흐려진다는 판단.
- 따라서 README.md는 기존 "6. 모니터링 — SRE 대시보드 & 알람 체계" 섹션의 표만 최소 업데이트하고, docs/port.md는 모니터링 섹션 + AI 워크플로우 섹션 양쪽을 모두 업데이트한다.

## 검토했으나 채택하지 않은 대안
### 대안 A: README.md에 AI 개발 워크플로우 섹션 신설
- 무엇: port.md의 "[AI 개발 워크플로우 구축]" 섹션과 유사한 내용을 README.md에도 추가
- 왜 안 썼나: 두 문서 간 내용 중복이 발생하고, 한쪽을 고치면 다른 쪽도 동기화해야 하는 유지보수 부담이 생긴다. README는 이미 "핵심 설계 결정"처럼 기술 중심 구조로 자리잡았고, AI 워크플로우는 port.md 고유의 색깔 섹션으로 보는 것이 자연스럽다. 사용자도 "후자(port.md 전용 유지)"를 명시적으로 선택했다.

### 대안 B: 표 구조를 새로 짜거나 섹션을 재작성
- 무엇: 기존 Tier 표/알람 표의 구조를 바꾸거나 새로운 하위 섹션을 만들어 e2e p95 내용을 강조
- 왜 안 썼나: Simplicity First 원칙 — 기존 표에 행을 추가하거나 문구를 교체하는 최소 diff로도 충분히 목적을 달성할 수 있다. 구조 변경은 리뷰 부담과 깨짐 위험만 키운다.

## 기존 코드베이스 컨벤션
- README.md "6. 모니터링" 섹션: Tier별 대시보드 표 + Prometheus 알람 규칙 표(P0~P3) 형식 (README.md:347-371)
- docs/port.md "[전 계층 통합 모니터링 환경]" 섹션: 6-Tier 대시보드 표(217-224행) + Prometheus 알람 표(245-249행)
- docs/port.md "[AI 개발 워크플로우 구축]" 섹션: 슬래시 명령어/스킬을 마크다운 표로 정리 (354-364행)

## 관련 파일/위치
- README.md:347-371 — "6. 모니터링 — SRE 대시보드 & 알람 체계" (Tier 표, 알람 규칙 표)
- docs/port.md:217-224, 245-249 — 모니터링 Tier 표, Prometheus 알람 표
- docs/port.md:354-364 — AI 워크플로우 자동화 스킬 표 (현재 `pr`/`spec-draft`/`spec-to-tickets` 누락)
- monitoring/prometheus/alert-rules.yml — `PaymentE2ELatencyHigh` 규칙 정의 (메트릭명·임계치 2초 확인용 출처)
- helm/promotion-monitoring/files/sre-dashboard.json — 신규 e2e p95 패널 정의 (패널 제목·쿼리 확인용 출처)

## 외부 참조
- docs/CHANGELOG.md v1.7.0 항목 — 이번에 반영할 변경 목록의 1차 출처
