import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// 커스텀 지표 생성: 시스템 타임아웃과 정상적인 비즈니스(성공/품절/큐 제한) 분리
const successCount = new Counter('business_success_202');
const soldOutCount = new Counter('business_sold_out_400');
const queueFullCount = new Counter('business_queue_full_503');
const systemErrorCount = new Counter('system_error_other');

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
    const url = 'http://promotion-server-a:8080/api/v1/promotions/purchase';

    const uniqueUserId = (__VU - 1) * 100 + __ITER + 1;

    const payload = JSON.stringify({
        userId: uniqueUserId,
        goodsId: 1,
        quantity: 1,
        paymentMethod: 'CARD',
        shippingAddress: '서울특별시 강남구 테헤란로 123',
        zipCode: '06234',
        phoneNumber: '010-1234-5678',
        email: `test${uniqueUserId}@weverse.com`,
        deliveryMemo: '문 앞에 두고 가주세요',
        clientIp: '192.168.0.1'
    });

    const params = {
        headers: { 'Content-Type': 'application/json' },
    };

    const res = http.post(url, payload, params);

    // 응답 상태에 따라 커스텀 카운터 증가
    if (res.status === 202) {
        successCount.add(1);
    } else if (res.status === 400) {
        soldOutCount.add(1);
    } else if (res.status === 503) {
        queueFullCount.add(1); // 큐 제한 인원 초과 방어 성공
    } else {
        systemErrorCount.add(1); // 서버 다운, 커넥션 타임아웃 등 진짜 에러
    }

    // k6 기본 리포트에도 명확하게 표시
    check(res, {
        'is accepted': (r) => r.status === 202,
        'is sold out': (r) => r.status === 400,
        'is queue full': (r) => r.status === 503,
    });
}