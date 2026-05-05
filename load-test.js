import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    scenarios: {
        assignment_scenario: {
            executor: 'per-vu-iterations',
            vus: 1000,          // 1,000명의 가상 유저
            iterations: 100,    // 1명당 100번씩 쏘기 (총 10만 건)
            maxDuration: '20s', // 넉넉하게 20초 타임아웃
        },
    },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)'],
};

export default function () {
    const url = 'http://weverse-server-a:8080/api/v1/promotions/purchase';

    // 겹치지 않는 10만 개의 고유 userId 생성 로직
    // __VU는 1~1000, __ITER는 0~99를 가집니다.
    // 예: 1번 유저의 첫 요청 = (1-1)*100 + 0 + 1 = 1
    // 예: 1000번 유저의 마지막 요청 = (1000-1)*100 + 99 + 1 = 100000
    const uniqueUserId = (__VU - 1) * 100 + __ITER + 1;

    const payload = JSON.stringify({
        userId: uniqueUserId,
        goodsId: 1, // 서버에 미리 생성해둔 상품 ID
        quantity: 1,
        paymentMethod: 'CARD',
        shippingAddress: '서울특별시 강남구 테헤란로 123',
        zipCode: '06234',
        phoneNumber: '010-1234-5678',
        email: `test${uniqueUserId}@weverse.com`, // 이메일도 다르게 세팅
        deliveryMemo: '문 앞에 두고 가주세요',
        clientIp: '192.168.0.1'
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
    };

    const res = http.post(url, payload, params);

    // 서버 A가 Queue에 무사히 넣었다면 202 Accepted를 반환함
    check(res, {
        'is status 202': (r) => r.status === 202,
    });

    // 1초에 10건씩 쏘기 위해 0.1초 대기 (1,000명이 동시에 0.1초마다 쏘면 10,000 TPS)
    sleep(0.1);
}