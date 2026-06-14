-- codebot DataQueryTools 읽기 전용 계정 생성 (order DB)
--
-- 적용 대상: promotion-mysql 컨테이너 (order DB)
-- 적용 방법: docker exec -i promotion-mysql mysql -u root -proot < docs/db/codebot-readonly-grants-order.sql
--
-- 로컬 개발용 비밀번호이며, 운영/EKS 환경에서는 별도 비밀번호로 재생성 후
-- helm/promotion-app/values.yaml의 codebot.datasource.order.password를 --set으로 일치시킨다.

CREATE USER IF NOT EXISTS 'codebot_ro'@'%' IDENTIFIED BY 'codebot_ro_pw';

-- orders: 개인정보 컬럼(shipping_address, zip_code, phone_number, email, delivery_memo, client_ip) 제외
GRANT SELECT (
    id, order_id, user_id, goods_id, quantity, payment_method, status, created_at, updated_at
) ON `order`.orders TO 'codebot_ro'@'%';

-- goods: 개인정보 컬럼 없음, 전체 허용
GRANT SELECT (
    id, name, stock
) ON `order`.goods TO 'codebot_ro'@'%';

FLUSH PRIVILEGES;
