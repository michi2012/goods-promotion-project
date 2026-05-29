# 체크리스트: mysql-exporter 추가 및 Grafana Alertmanager datasource 연결

- 마지막 업데이트: 2026-05-29

## 진행 상황
- [x] 단계 1: docker-compose.yml에 mysql-a-exporter, mysql-c-exporter 추가
  - [ ] 검증: docker ps에서 두 컨테이너 Up 확인 (사용자 실행 필요)
- [x] 단계 2: prometheus.yml에 mysql scrape job 추가
  - [ ] 검증: http://localhost:9090/targets 에서 mysql job 두 타겟 State=UP (사용자 실행 필요)
- [x] 단계 3: datasource.yml에 Alertmanager datasource 추가
  - [ ] 검증: Grafana Data sources에서 "Data source connected" 확인 (사용자 실행 필요)
- [x] 단계 4: infra-diagram.md 업데이트
  - [x] 검증: Mermaid 문법 이상 없음 확인

## 최종 검증
- [ ] curl http://localhost:9104/metrics 에서 mysql_up 1 확인 (사용자 실행 필요)
- [ ] curl http://localhost:9105/metrics 에서 mysql_up 1 확인 (사용자 실행 필요)
- [x] 변경 사항이 plan.md의 비범위를 침범하지 않았는지 확인
- [ ] 의도하지 않은 파일 변경이 없는지 git diff로 최종 확인

## 발견 사항
- (없음)
