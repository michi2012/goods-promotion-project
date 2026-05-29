# 체크리스트: cAdvisor 추가 및 MySQL CPU/메모리 모니터링 완성

- 마지막 업데이트: 2026-05-29

## 진행 상황
- [x] 단계 1: docker-compose.yml에 cAdvisor 서비스 추가
  - [x] 검증 통과 (`docker-compose config`)
- [x] 단계 2: prometheus.yml에 cadvisor scrape 설정 추가
  - [x] 검증 통과 (`docker-compose config`)
- [x] 단계 3: Grafana 대시보드에 MySQL CPU/메모리 패널 추가
  - [x] 검증 통과 (`Get-Content sre-dashboard.json | ConvertFrom-Json`)
- [x] 단계 4: alert-rules.yml에 MySQL CPU/메모리 알람 추가 및 번호 재정리
  - [x] 검증 통과 (알람 22개 전체 확인)

## 최종 검증
- [ ] 변경 사항이 plan.md의 "비범위"를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항
- 없음
