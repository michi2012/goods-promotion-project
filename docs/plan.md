# 계획서: server-a DB 이름 promotion → order 변경

- 작성일: 2026-06-06

## 목표
server-a(주문 서버)가 사용하는 MySQL 스키마 이름을 `promotion`에서 `order`로 변경한다.
server-c의 `payment`와 일관된 역할 기반 명명 체계를 갖추는 것이 목적이다.

## 성공 기준
- [ ] 6개 파일 모두 수정 완료 (`git diff --stat` 확인)
- [ ] datasource URL / MYSQL_DATABASE 맥락에서 `promotion` 잔존 없음
- [ ] `docker compose up` 시 `order` 스키마로 MySQL 컨테이너 기동 가능 (육안)

## 비범위 (Out of Scope)
- 프로젝트명 `promotion` (디렉토리명, Helm 차트명 `promotion-app`, 네임스페이스) 변경 없음
- server-b / server-c / gateway / aiops 설정 변경 없음
- Flyway 마이그레이션 SQL 변경 없음 (DB 이름 미참조 확인)
- 기존 MySQL 볼륨 데이터 마이그레이션 없음 (dev 환경)
- K8s `--set` 배포 시 실제 RDS 엔드포인트 URL 값 변경 없음 (사용자 직접 관리)

## 단계별 작업 계획

### 단계 1: serverA/src/main/resources/application.yaml
- 변경 내용: 기본값 datasource URL `jdbc:mysql://localhost:3306/promotion?` → `jdbc:mysql://localhost:3306/order?`
- 검증: 파일 육안 확인
- 예상 소요: 짧음

### 단계 2: docker-compose.yml
- 변경 내용: mysql 서비스 `MYSQL_DATABASE: promotion` → `MYSQL_DATABASE: order` / server-a `SPRING_DATASOURCE_URL` 내 `/promotion?` → `/order?`
- 검증: 파일 육안 확인
- 예상 소요: 짧음

### 단계 3: docker-compose.infra.yml
- 변경 내용: `MYSQL_DATABASE: promotion` → `MYSQL_DATABASE: order`
- 검증: 파일 육안 확인
- 예상 소요: 짧음

### 단계 4: helm/promotion-app/values.yaml
- 변경 내용: 주석 예시 URL `/promotion?` → `/order?`
- 검증: 파일 육안 확인
- 예상 소요: 짧음

### 단계 5: README.md
- 변경 내용: 서비스 테이블 `promotion DB` → `order DB`
- 검증: 파일 육안 확인
- 예상 소요: 짧음

### 단계 6: docs/infra-diagram.md
- 변경 내용: `RDS_A["RDS\npromotion DB"]` → `RDS_A["RDS\norder DB"]`
- 검증: 파일 육안 확인
- 예상 소요: 짧음

## 리스크 및 대응
- 기존에 `docker compose up`으로 생성된 MySQL 볼륨이 있으면 스키마가 자동 생성되지 않음 → `docker compose down -v` 후 재기동 필요 (완료 후 안내)

## 의존성
없음. 단순 문자열 치환.
