// PG 대사 배치 기준선 측정용 시드 생성기 (BL-047, docs/specs/pg-reconciliation-batch.md ③).
// 대사일 D 하루치 serverC `payments` INSERT SQL과 PG 거래 파일(CSV)을 직접 만든다 (모의 PG 원장 경유 X).
// 같은 인자면 같은 파일이 나온다 (고정 seed PRNG). 의존성 없음.
//
//   node perf/seed/pg-reconciliation-seed.mjs --date 2026-10-01 --payments 100000 [--seed 47] [--out perf/seed/out]
//
// 결제 한 건의 구성 (비율은 RATIOS):
//   NORMAL    우리 승인(D) + PG 승인(D, 우리보다 조금 앞섬)             → 일치
//   CANCELLED 위 + 우리 취소(D) + PG 취소(D)                            → 일치 (이벤트 2개)
//   BOUNDARY  PG 승인 D 23:59:5x, 우리 승인 D+1 00:00:0x                → 경계 건, 불일치 아님 (P-5)
//   OURS_ONLY 우리 승인(D), PG 줄 없음                                   → ① 1건
//   PG_ONLY   PG 승인(D), payments 행 없음                              → ② 1건
//   AMOUNT    우리 승인(D) + PG 승인(D), PG 금액이 다름                  → ③ 1건
//   STATUS    우리 FAILED(승인 기록 없음) + PG 승인(D)                   → ④ 1건
import { mkdirSync, createWriteStream } from 'node:fs';
import { join } from 'node:path';
import { once } from 'node:events';

const RATIOS = [
  ['CANCELLED', 0.05],
  ['BOUNDARY', 0.0005],
  ['OURS_ONLY', 0.001],
  ['PG_ONLY', 0.001],
  ['AMOUNT', 0.001],
  ['STATUS', 0.001],
]; // 나머지는 NORMAL

const ROWS_PER_INSERT = 1000;
const DAY_MS = 24 * 60 * 60 * 1000;

function parseArgs(argv) {
  const args = { seed: 47, out: 'perf/seed/out' };
  for (let i = 0; i < argv.length; i += 2) {
    const key = argv[i].replace(/^--/, '');
    args[key] = argv[i + 1];
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(args.date ?? '') || !(Number(args.payments) > 0)) {
    console.error('사용법: node pg-reconciliation-seed.mjs --date YYYY-MM-DD --payments N [--seed 47] [--out DIR]');
    process.exit(1);
  }
  return { date: args.date, payments: Number(args.payments), seed: Number(args.seed), out: args.out };
}

// mulberry32 — 작고 결정적인 PRNG
function prng(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// 시각은 KST 벽시계 그대로 다룬다: D 00:00을 0으로 둔 밀리초 오프셋 → 'YYYY-MM-DDTHH:mm:ss.SSSSSS'
function formatter(date) {
  const base = Date.parse(`${date}T00:00:00Z`);
  return (offsetMs, sep = 'T') => {
    const iso = new Date(base + Math.floor(offsetMs)).toISOString(); // UTC로 계산하지만 벽시계 값으로만 쓴다
    const micros = String(Math.floor((offsetMs % 1) * 1000)).padStart(3, '0');
    return `${iso.slice(0, 10)}${sep}${iso.slice(11, 23)}${micros}`;
  };
}

function pickKind(r) {
  let acc = 0;
  for (const [kind, ratio] of RATIOS) {
    acc += ratio;
    if (r < acc) return kind;
  }
  return 'NORMAL';
}

async function main() {
  const { date, payments, seed, out } = parseArgs(process.argv.slice(2));
  const ymd = date.replaceAll('-', '');
  mkdirSync(out, { recursive: true });
  const sqlPath = join(out, `payments-seed-${ymd}.sql`);
  const csvPath = join(out, `pg-transactions-${ymd}.csv`);
  const sql = createWriteStream(sqlPath, { encoding: 'utf8' });
  const csv = createWriteStream(csvPath, { encoding: 'utf8' });
  const write = async (stream, text) => {
    if (!stream.write(text)) await once(stream, 'drain');
  };

  const rand = prng(seed);
  const at = formatter(date);
  const sqlAt = (ms) => at(ms, ' ');
  const counts = { NORMAL: 0, CANCELLED: 0, BOUNDARY: 0, OURS_ONLY: 0, PG_ONLY: 0, AMOUNT: 0, STATUS: 0 };
  let ourEvents = 0;
  let pgLines = 0;
  let rows = [];

  const flushRows = async () => {
    if (rows.length === 0) return;
    await write(sql, 'INSERT INTO payments (order_id, user_id, goods_id, quantity, payment_method, shipping_address, '
      + 'zip_code, phone_number, email, status, created_at, amount, currency, approved_at, cancelled_at) VALUES\n'
      + rows.join(',\n') + ';\n');
    rows = [];
  };
  const q = (v) => (v === null ? 'NULL' : `'${v}'`);

  await write(csv, 'order_id,event_type,amount,occurred_at\n');
  await write(sql, `-- PG 대사 시드 date=${date} payments=${payments} seed=${seed}\n`);

  for (let i = 1; i <= payments; i++) {
    const kind = pickKind(rand());
    counts[kind]++;
    const orderId = `seed-${ymd}-${i}`;
    const amount = (1 + Math.floor(rand() * 100)) * 1000;
    // 우리 승인 시각: D 00:00:10 ~ D 23:59:00 (의도한 경계 건 외에는 날짜 경계에 걸리지 않게)
    let ourApprove = 10_000 + rand() * (DAY_MS - 70_000);
    let pgApprove = ourApprove - (10 + rand() * 900); // PG 시각 ≤ 우리 기록 시각
    if (kind === 'BOUNDARY') {
      pgApprove = DAY_MS - 1 - rand() * 9_000; // D 23:59:51 ~ 23:59:59.999
      ourApprove = DAY_MS + 1 + rand() * 9_000; // D+1 00:00:00 ~ 00:00:09
    }

    let status = 'PAID';
    let approvedAt = sqlAt(ourApprove);
    let cancelledAt = null;
    const pg = [];

    if (kind === 'CANCELLED') {
      const ourCancel = ourApprove + 1_000 + rand() * (DAY_MS - 60_000 - ourApprove);
      status = 'CANCELLED';
      cancelledAt = sqlAt(ourCancel);
      pg.push(['APPROVE', amount, pgApprove], ['CANCEL', amount, ourCancel - (10 + rand() * 900)]);
    } else if (kind === 'STATUS') {
      status = 'FAILED';
      approvedAt = null;
      pg.push(['APPROVE', amount, pgApprove]);
    } else if (kind === 'AMOUNT') {
      pg.push(['APPROVE', amount + 500, pgApprove]);
    } else if (kind !== 'OURS_ONLY') {
      pg.push(['APPROVE', amount, pgApprove]); // NORMAL, BOUNDARY, PG_ONLY
    }

    if (kind !== 'PG_ONLY') {
      rows.push(`(${q(orderId)},${1 + (i % 50_000)},${1 + (i % 20)},1,'CARD','seoul seed-st ${i}','04524',`
        + `'010-0000-0000','seed${i}@example.com',${q(status)},${q(sqlAt(ourApprove - 1_000))},${amount},'KRW',`
        + `${q(approvedAt)},${q(cancelledAt)})`);
      if (rows.length === ROWS_PER_INSERT) await flushRows();
      if (approvedAt !== null && kind !== 'BOUNDARY') ourEvents++;
      if (cancelledAt !== null) ourEvents++;
    }
    for (const [eventType, pgAmount, pgAt] of pg) {
      await write(csv, `${orderId},${eventType},${pgAmount},${at(pgAt)}\n`);
      pgLines++;
    }
  }
  await flushRows();
  sql.end();
  csv.end();
  await Promise.all([once(sql, 'finish'), once(csv, 'finish')]);

  const summary = {
    date, payments, seed,
    files: { sql: sqlPath, csv: csvPath },
    composition: counts,
    ourEventsInDay: ourEvents,
    pgLines,
    expectedMismatches: {
      OURS_ONLY: counts.OURS_ONLY,
      PG_ONLY: counts.PG_ONLY,
      AMOUNT: counts.AMOUNT,
      STATUS: counts.STATUS,
    },
  };
  console.log(JSON.stringify(summary, null, 2));
}

await main();
