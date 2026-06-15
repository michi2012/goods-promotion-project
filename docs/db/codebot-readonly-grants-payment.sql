-- codebot DataQueryTools 읽기 전용 계정 생성 (payment DB)
--
-- 적용 대상: promotion-mysql-c 컨테이너 (payment DB)
-- 적용 방법: docker exec -i promotion-mysql-c mysql -u root -proot < docs/db/codebot-readonly-grants-payment.sql
--
-- 로컬 개발용 비밀번호이며, 운영/EKS 환경에서는 별도 비밀번호로 재생성 후
-- helm/promotion-app/values.yaml의 codebot.datasource.payment.password를 --set으로 일치시킨다.

CREATE USER IF NOT EXISTS 'codebot_ro'@'%' IDENTIFIED BY 'codebot_ro_pw';

-- payments: 개인정보 컬럼(shipping_address, zip_code, phone_number, email, delivery_memo, client_ip) 제외
GRANT SELECT (
    id, order_id, user_id, goods_id, quantity, payment_method, status, created_at
) ON payment.payments TO 'codebot_ro'@'%';

FLUSH PRIVILEGES;
