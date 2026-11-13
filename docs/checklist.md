# 체크리스트: Helm 로컬 values DB 이름 수정 + 죽은 Eureka 설정 제거 (BL-010, BL-012)

- 마지막 업데이트: 2026-11-13

## [완료] BL-010 + BL-012 (PR #29)

### 진행 상황
- [x] 단계 1: 변경 전 렌더링 기준선 저장 (canary 3종 활성화 포함)
  - [x] 검증 통과 (`helm template` 두 조합 exit 0, EUREKA 11건)
- [x] 단계 2: BL-010 values-local.yaml DB 이름 `order`
- [x] 단계 3: BL-012 EUREKA env 11곳 + values 키 8개 + `discovery:` 섹션 삭제
  - [x] 검증 통과 (`helm/` grep 0건)
- [x] 단계 4: 렌더링 diff + `helm lint`
  - [x] diff에 의도한 변경만 있음 (기본: EUREKA 22줄 삭제 / local: + DB URL 2줄, `rollme` 무작위 값 제외)
  - [x] `helm lint` 두 조합 0 failed
- [x] 단계 5: 문서 갱신 (backlog BL-024 추가)

### 최종 검증
- [x] 성공 기준 4개 확인
- [x] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인 (앱 yml·docker-compose 미변경)
- [x] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인 (Helm 12개 + values-local + 문서 4개)

## 발견 사항 (작업 중 별도 처리 필요한 것)
- ~~codebot `application-k8s.yml` 없음~~ → 백로그 BL-024로 이전 완료
- 계획 수정: 대상 템플릿은 10개가 아니라 11개 (server-c·user-service 포함, 렌더링으로 확인)
