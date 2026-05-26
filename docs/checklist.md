# 체크리스트: Codex MCP 교차 코드 리뷰어 추가

- 마지막 업데이트: 2026-05-26

## 진행 상황

- [ ] 단계 1: .mcp.json에 codex 서버 항목 추가
  - [ ] JSON 문법 유효성 확인

- [ ] 단계 2: OPENAI_API_KEY 주입 및 codex 활성화
  - [ ] .mcp.json의 OPENAI_API_KEY 플레이스홀더를 실제 키로 교체 (사용자 직접 입력)
  - [ ] settings.json enabledMcpServers에 "codex" 추가
  - [ ] Claude Code 재시작 후 `/mcp`로 codex 서버 connected 상태 확인

- [ ] 단계 3: codex-reviewer 에이전트 파일 생성
  - [ ] 실제 노출 도구명 확인 후 에이전트 파일 내 도구명 조정
  - [ ] "Codex로 교차 리뷰해줘" 호출 시 에이전트 활성화 확인

## 최종 검증
- [ ] 기존 code-reviewer 동작 영향 없음 확인
- [ ] plan.md 비범위 침범 없음 확인 (자동 트리거, 결과 병합 등 미구현)
- [ ] git diff --stat으로 변경 파일 최종 확인

## 발견 사항 (작업 중 별도 처리 필요한 것)
- .mcp.json에 mysql 자격증명 + OpenAI API Key가 평문 저장됨 → .gitignore 추가 여부 사용자 판단 필요
