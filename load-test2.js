import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// 구매 지표
const purchaseSuccessCount = new Counter('purchase_success_202');
const purchaseSoldOutCount = new Counter('purchase_sold_out_400');
const purchaseQueueFullCount = new Counter('purchase_queue_full_503');
const purchaseErrorCount = new Counter('purchase_error_other');

// 조회 지표
const readSuccessCount = new Counter('read_success_200');
const readErrorCount = new Counter('read_error_other');

export const options = {
    scenarios: {
        flash_sale_scenario: {
            executor: 'shared-iterations',
            vus: 1000,
            iterations: 100000,
            maxDuration: '2m',
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)'],
};

export default function () {
    // 0부터 1 사이의 난수 생성
    const randomValue = Math.random();

    // 80% 확률로 조회 트래픽 발생
    if (randomValue < 0.8) {
        // 조회 서버는 server-b 입니다.
        const readUrl = 'http://weverse-server-b:8081/api/v1/goods/2/stock';
        const res = http.get(readUrl);

        if (res.status === 200) {
            readSuccessCount.add(1);
        } else {
            readErrorCount.add(1);
        }

        check(res, {
            'read success': (r) => r.status === 200,
        });

    } else {
        // 20% 확률로 구매 트래픽 발생
        // 구매 서버는 server-a 입니다.
        const purchaseUrl = 'http://weverse-server-a:8080/api/v1/promotions/purchase';
        const uniqueUserId = (__VU - 1) * 100 + __ITER + 1;

        const payload = JSON.stringify({
            userId: uniqueUserId,
            goodsId: 2,
            quantity: 1,
            paymentMethod: 'CARD',
            shippingAddress: '서울특별시 강남구 테헤란로 123',
            zipCode: '06234',
            phoneNumber: '010-1234-5678',
            email: 'test' + uniqueUserId + '@weverse.com',
            deliveryMemo: '문 앞에 두고 가주세요',
            clientIp: '192.168.0.1'
        });

        const params = {
            headers: { 'Content-Type': 'application/json' },
        };

        const res = http.post(purchaseUrl, payload, params);

        if (res.status === 202) {
            purchaseSuccessCount.add(1);
        } else if (res.status === 400) {
            purchaseSoldOutCount.add(1);
        } else if (res.status === 503) {
            purchaseQueueFullCount.add(1);
        } else {
            purchaseErrorCount.add(1);
        }

        check(res, {
            'purchase accepted': (r) => r.status === 202,
            'purchase sold out': (r) => r.status === 400,
            'purchase queue full': (r) => r.status === 503,
        });
    }
}