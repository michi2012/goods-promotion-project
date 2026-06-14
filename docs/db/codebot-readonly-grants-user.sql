-- codebot DataQueryTools 읽기 전용 계정 생성 (user DB)
--
-- 적용 대상: promotion-mysql-user 컨테이너 (user DB)
-- 적용 방법: docker exec -i promotion-mysql-user mysql -u root -proot < docs/db/codebot-readonly-grants-user.sql
--
-- 로컬 개발용 비밀번호이며, 운영/EKS 환경에서는 별도 비밀번호로 재생성 후
-- helm/promotion-app/values.yaml의 codebot.datasource.user.password를 --set으로 일치시킨다.

CREATE USER IF NOT EXISTS 'codebot_ro'@'%' IDENTIFIED BY 'codebot_ro_pw';

-- users: 개인정보 컬럼(email, password, phone_number) 제외
GRANT SELECT (
    id, user_id, username, role, created_at, updated_at
) ON `user`.users TO 'codebot_ro'@'%';

FLUSH PRIVILEGES;
