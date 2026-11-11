// 차량 앱 대신 차 역할을 하는 시뮬레이터 (웹·서버 개발용)
// 픽업을 시작하고, 차를 픽업 장소 쪽으로 움직이며 server-lite에 위치를 보낸다.
// 받는 사람이 웹에서 장소를 바꾸면 운전자가 승인한 것처럼 처리한다.
//
// 실행: node sim-car.js            → 장소를 정해서 공유 (권한: 제안)
//       node sim-car.js AUTO       → 권한 지정 (VIEW | SUGGEST | AUTO)
//       node sim-car.js SUGGEST noplace → 받는 사람이 장소를 정하게
// 환경변수: SERVER(서버 주소) · SPEED(초당 m, 기본 60) · NOTIFY(나갈 타이밍 알림 초, 기본 20)

const SERVER = process.env.SERVER || 'http://localhost:8787';
const permission = (process.argv[2] || 'SUGGEST').toUpperCase();
const noPlace = process.argv.includes('noplace');

const STEP_MS = 2000;      // 위치 전송 간격 (실제 앱은 5초)
const SPEED_MPS = Number(process.env.SPEED || 60); // 시연용으로 빠르게 (실제 시속 216km 수준). 알림 시험은 SPEED=20 정도로

let place = noPlace ? null : { name: '강남역 11번 출구', lat: 37.49795, lng: 127.02762 };
let car = { lat: 37.5090, lng: 127.0110 }; // 출발: 약 2km 떨어진 곳

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

let driverToken = ''; // 픽업을 만들 때 서버가 차량에게만 주는 비밀키

async function get(path) {
  const r = await fetch(SERVER + path);
  if (!r.ok) throw new Error(`${r.status}`);
  return r.json();
}

async function post(path, body) {
  const r = await fetch(SERVER + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...(driverToken ? { Authorization: `Bearer ${driverToken}` } : {}) },
    body: JSON.stringify(body || {}),
  });
  return r.json();
}

// 두 좌표 사이 거리(m)
function dist(a, b) {
  const R = 6371000, rad = Math.PI / 180;
  const dLat = (b.lat - a.lat) * rad, dLng = (b.lng - a.lng) * rad;
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(a.lat * rad) * Math.cos(b.lat * rad) * Math.sin(dLng / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}

function heading(a, b) {
  return (Math.atan2(b.lng - a.lng, b.lat - a.lat) * 180 / Math.PI + 360) % 360;
}

// 남은 경로 (도로를 따라가는 점들). 서버가 TMAP으로 받아 둔 차 → 만날 곳 경로를 쓴다.
// TMAP 키가 없거나 실패하면 직선으로 간다.
let route = [];

async function loadRoute(id) {
  try {
    const r = await get(`/api/sessions/${id}/route`);
    const pts = (r.path || []).map(([lat, lng]) => ({ lat, lng }));
    if (pts.length < 2) throw new Error('경로 없음');
    route = [...pts, { lat: place.lat, lng: place.lng }];
    console.log(`\n도로 경로 받음 · ${Math.round(pathLen(car, route))}m`);
  } catch (e) {
    route = [{ lat: place.lat, lng: place.lng }];
    console.log(`\n도로 경로를 못 받아서 직선으로 가요 (${e.message})`);
  }
}

// 지금 위치에서 남은 경로를 따라간 거리
function pathLen(from, pts) {
  let d = 0, prev = from;
  for (const p of pts) { d += dist(prev, p); prev = p; }
  return d;
}

// 경로를 따라 step 미터만큼 이동
function advance(step) {
  while (step > 0 && route.length) {
    const next = route[0];
    const d = dist(car, next);
    const h = d > 0.5 ? heading(car, next) : car.heading || 0;
    if (d <= step) {
      car = { lat: next.lat, lng: next.lng, heading: h };
      route.shift();
      step -= d;
    } else {
      const t = step / d;
      car = { lat: car.lat + (next.lat - car.lat) * t, lng: car.lng + (next.lng - car.lng) * t, heading: h };
      step = 0;
    }
  }
}

// 같이 켜질 때 서버가 아직 안 떴을 수 있어서 최대 10초 기다린다
async function waitServer() {
  for (let i = 0; i < 20; i++) {
    try { await fetch(SERVER + '/api/sessions/ping'); return; } catch { await sleep(500); }
  }
}

async function main() {
  await waitServer();
  const created = await post('/api/sessions', {
    driverLabel: '지훈',
    vehicle: { model: '아이오닉 5', color: '흰색' },
    contactName: '김민수',
    contactPhone: '010-0000-0001',
    permission,
    place,
    notifyBeforeSec: Number(process.env.NOTIFY || 20), // 시연용: 도착 20초 전에 '나갈 타이밍'
  });
  console.log(`\n픽업 시작 (권한 ${permission})`);
  console.log(`받는 사람 링크 → ${created.link}`);
  console.log(`이 컴퓨터에서 열기 → ${SERVER}/p/${created.id}\n`);
  const id = created.id;
  driverToken = created.driverToken || '';

  let viewed = false;
  let lastNext = null;
  let arrivedAt = null;
  let needRoute = !!place;

  while (true) {
    // 장소가 없으면 받는 사람이 정할 때까지 제자리
    let remain = null;
    let state = 'DRIVING';

    if (place && needRoute) {
      // 서버가 차 위치를 알아야 경로를 줄 수 있어서, 먼저 지금 위치를 한 번 보낸다
      await post(`/api/sessions/${id}/location`, { ...car, heading: car.heading || 0, state: 'DRIVING' });
      await loadRoute(id);
      needRoute = false;
    }
    if (place) remain = pathLen(car, route.length ? route : [place]);

    if (place && remain > 15) {
      advance(SPEED_MPS * STEP_MS / 1000);
      remain = pathLen(car, route.length ? route : [place]);
    }
    if (place && remain <= 15) {
      state = 'ARRIVED';
      arrivedAt = arrivedAt || Date.now();
    }

    // 도착(비상등 켬) 25초 뒤 탑승, 3초 뒤 종료
    if (arrivedAt && Date.now() - arrivedAt > 25000) {
      await post(`/api/sessions/${id}/location`, { ...car, heading: car.heading || 0, remainSec: 0, remainMeters: 0, state: 'BOARDED', hazard: true });
      console.log('탑승 (문 열림) → 공유 종료');
      await sleep(3000);
      await post(`/api/sessions/${id}/end`);
      console.log('종료. 링크가 만료됐어요.');
      return;
    }

    const res = await post(`/api/sessions/${id}/location`, {
      lat: car.lat,
      lng: car.lng,
      heading: car.heading || 0,
      remainSec: place ? Math.round(remain / SPEED_MPS) : null,
      remainMeters: place ? Math.round(remain) : null,
      state,
      hazard: state === 'ARRIVED',
    });
    if (res.nextPlace && res.nextPlace.name !== lastNext) { lastNext = res.nextPlace.name; console.log(`\n탑승 후 목적지 받음 → ${lastNext}`); }

    if (res.viewedAt && !viewed) { viewed = true; console.log('받는 사람이 링크를 열었어요'); }

    const ch = res.pendingChange;
    if (ch) {
      if (ch.applied) {
        console.log(`장소 바로 반영 → ${ch.place.name} ${ch.message ? `"${ch.message}"` : ''}`);
      } else {
        console.log(`장소 변경 요청 → ${ch.place.name} ${ch.message ? `"${ch.message}"` : ''} (3초 뒤 운전자 승인)`);
        await sleep(3000);
      }
      await post(`/api/sessions/${id}/resolve`, { accepted: true });
      place = ch.place;
      arrivedAt = null;
      needRoute = true; // 장소가 바뀌면 새 경로
    }

    if (state === 'DRIVING' && place) process.stdout.write(`  주행 중 · 남은 ${Math.round(remain)}m\r`);
    if (state === 'ARRIVED' && Date.now() - arrivedAt < STEP_MS + 100) console.log('\n도착 · 비상등 켬 (25초 뒤 탑승 처리)');
    await sleep(STEP_MS);
  }
}

main().catch((e) => {
  console.error('서버에 연결할 수 없어요. 먼저 node server.js 를 실행하세요.\n', e.message);
  process.exit(1);
});
