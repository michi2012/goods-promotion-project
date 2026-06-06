# 체크리스트: server-a DB 이름 promotion → order 변경

- 마지막 업데이트: 2026-06-06

## 진행 상황
- [x] 단계 1: serverA/src/main/resources/application.yaml
- [x] 단계 2: docker-compose.yml
- [x] 단계 3: docker-compose.infra.yml
- [x] 단계 4: helm/promotion-app/values.yaml
- [x] 단계 5: README.md (서비스 테이블 + 시퀀스 다이어그램 2곳)
- [x] 단계 6: docs/infra-diagram.md
- [x] 추가 발견: docs/arch-snapshot.md

## 최종 검증
- [x] `git diff --stat` — DB 변경 대상 7개 파일 + docs/infra-diagram.md(다이어그램 정리 포함) + plan/context/checklist 확인
- [x] datasource URL / MYSQL_DATABASE 맥락에서 `promotion` 잔존 없음 (grep 확인)
- [x] plan.md 비범위 침범 없음 확인 (프로젝트명 promotion 변경 없음)
