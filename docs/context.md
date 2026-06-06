# 맥락 노트: server-a DB 이름 promotion → order 변경

## 왜 이 방식을 선택했는가
server-c DB 이름이 `payment`로 역할 기반 명명인 반면, server-a만 프로젝트 이름인 `promotion`을 DB 이름으로 사용하고 있었다. 일관성을 위해 `order`로 변경. 단순 문자열 치환이며 아키텍처 변경 없음.

## 검토했으나 채택하지 않은 대안
없음. 대안이 없는 단순 명명 수정.

## 관련 파일/위치
- `serverA/src/main/resources/application.yaml` — 로컬 개발용 기본값 datasource URL
- `docker-compose.yml` — MYSQL_DATABASE 및 SPRING_DATASOURCE_URL
- `docker-compose.infra.yml` — MYSQL_DATABASE
- `helm/promotion-app/values.yaml` — 주석 예시 URL
- `README.md` — 서비스 역할 테이블
- `docs/infra-diagram.md` — K8s 토폴로지 다이어그램 노드 레이블
