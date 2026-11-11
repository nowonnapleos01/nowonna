// 나온나 server-lite
// 차량 앱 ↔ 받는 사람 웹을 잇는 픽업 서버 (Node 내장 모듈만 사용, 데이터는 메모리에만 둔다).
//
//   · 픽업(세션) API      차량 앱이 만들고 위치를 보내면, 받는 사람 웹이 실시간(SSE)으로 받는다. 형식은 API.md
//   · 보안               차량 전용 요청은 driverToken 확인, 링크는 16자리 무작위 id · 2시간 뒤 자동 만료
//   · TMAP 호출 대행      경로·도보 시간·주소·장소 검색. 앱키는 서버에만 두고 결과는 캐시
//   · 휴대폰 알림         웹 푸시(VAPID + aes128gcm)를 직접 구현
//   · 받는 사람 웹        /p/:id (web/index.html), 개인정보 처리방침 /privacy
//   · 모의 주행(시연용)   DEMO_DRIVE=1 이면 차가 도로를 따라 달린 것처럼 위치를 만든다
//
// 실행: node server.js   (기본 포트 8787)
// 설정: 같은 폴더의 .env (.env.example 참고). TMAP_APP_KEY가 없으면 개발용 OpenStreetMap 지도로 열린다.
// 실서비스에서 더할 것: 링크 문자 발송, DB 저장 (README 참고)

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');
const crypto = require('crypto');

// .env 읽기 (의존성 없이). 이미 환경변수로 넣은 값이 있으면 그걸 먼저 쓴다.
try {
  for (const line of fs.readFileSync(path.join(__dirname, '.env'), 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && !(m[1] in process.env)) process.env[m[1]] = m[2];
  }
} catch { /* .env 없음 */ }

const PORT = Number(process.env.PORT || 8787);
const TMAP_APP_KEY = process.env.TMAP_APP_KEY || '';
// 링크 주소: 같은 와이파이의 휴대폰이 열 수 있게 맥의 내부 IP를 쓴다 (PUBLIC_HOST로 덮어쓰기 가능)
function lanIp() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const a of list || []) if (a.family === 'IPv4' && !a.internal) return a.address;
  }
  return 'localhost';
}
const PUBLIC_HOST = process.env.PUBLIC_HOST || `${lanIp()}:${PORT}`;

// 받는 사람 링크의 앞부분. 외부 주소(https)가 있으면 그걸 쓴다.
//   PUBLIC_URL=https://xxxx.trycloudflare.com  (Cloudflare Tunnel 등)
// .env 의 PUBLIC_URL 은 링크를 만들 때마다 다시 읽어서, 터널 주소가 바뀌어도 서버를 다시 켤 필요가 없다.
const START_PUBLIC_URL = process.env.PUBLIC_URL || '';
function publicUrl() {
  let url = START_PUBLIC_URL;
  if (!url) {
    try {
      const m = fs.readFileSync(path.join(__dirname, '.env'), 'utf8').match(/^\s*PUBLIC_URL\s*=\s*(\S+)\s*$/m);
      if (m) url = m[1];
    } catch { /* .env 없음 */ }
  }
  return (url || `http://${PUBLIC_HOST}`).replace(/\/+$/, '');
}
const sessions = new Map(); // id → session
const watchers = new Map(); // id → Set<res> (웹의 실시간 구독, SSE)

function newId() {
  return crypto.randomBytes(12).toString('base64url'); // 링크 id 16글자 (96비트): 남의 링크를 찍어서 맞힐 수 없게
}

// ---------- 보안 ----------
// 링크(id)는 받는 사람에게 가는 값이라 누구나 알 수 있다. 그래서 차량만 하는 요청
// (위치 보고·변경 승인·공유 종료)은 픽업을 만들 때 차량에게만 준 비밀키(driverToken)가 있어야 받는다.
const DRIVER_ACTIONS = new Set(['location', 'resolve', 'end']);
const LINK_TTL_MS = Number(process.env.LINK_TTL_MIN || 120) * 60 * 1000; // 링크 수명: 시작 후 2시간 (테스트할 땐 LINK_TTL_MIN으로 줄이기)
const KEEP_ENDED_MS = 24 * 60 * 60 * 1000; // 끝난 픽업은 "종료됨" 화면용으로 하루만 두고 지운다

function newDriverToken() {
  return crypto.randomBytes(32).toString('base64url'); // 43글자, 추측 불가
}

function isDriver(req, s) {
  const m = /^Bearer\s+(\S+)$/.exec(req.headers.authorization || '');
  if (!m) return false;
  const a = Buffer.from(m[1]), b = Buffer.from(s.driverToken);
  return a.length === b.length && crypto.timingSafeEqual(a, b); // 글자를 비교하는 시간으로 값을 알아내지 못하게
}

// 공유 종료: 링크 만료 + 차 위치·비상등·받는 사람 전화번호 삭제
function endSession(s, why) {
  if (s.state === 'ENDED') return;
  s.state = 'ENDED';
  s.car = null;
  s.hazard = false;
  s.contactPhone = '';
  s.pendingChange = null;
  s.routeCache = null;
  s.walkCache = null;
  s.push = null; // 알림 받던 브라우저 정보도 지운다
  s.endedAt = Date.now();
  broadcast(s);
  console.log(`[종료] ${s.id} ${why}`);
}

// 1분마다: 2시간 지난 픽업은 끝내고, 끝난 지 하루 지난 픽업은 메모리에서 지운다
setInterval(() => {
  const now = Date.now();
  for (const s of sessions.values()) {
    if (s.state !== 'ENDED' && now > s.expiresAt) endSession(s, '(2시간 만료)');
    else if (s.state === 'ENDED' && now - s.endedAt > KEEP_ENDED_MS) { sessions.delete(s.id); watchers.delete(s.id); }
  }
}, 60_000).unref();

function publicView(s) {
  // 웹에 보여줄 값만 (전화번호 등은 빼고)
  return {
    id: s.id,
    driverLabel: s.driverLabel,
    contactName: s.contactName,
    permission: s.permission,
    place: s.place,
    originalPlace: s.originalPlace, // 받는 사람이 바꾸기 전 장소 (W4 "원래: …")
    car: s.car,
    vehicle: s.vehicle, // { model, color, plate } 차 찾기 (W5)
    hazard: s.hazard,
    state: s.state,
    notifyBeforeSec: s.notifyBeforeSec,
    pendingChange: s.pendingChange,
    lastResolution: s.lastResolution, // 운전자의 마지막 답 (W4)
    nextPlace: s.nextPlace, // 탑승 후 목적지 (W7 · 차량 C5)
    viewedAt: s.viewedAt,
    boardedAt: s.boardedAt,
    endedAt: s.endedAt,
    expiresAt: s.expiresAt, // 링크 자동 만료 시각
    now: Date.now(), // 웹이 "마지막 위치 ○초 전"을 계산할 때 시계 차이 보정용
  };
}

function cleanPlace(p) {
  return { name: String(p.name || '').slice(0, 40) || '지도에서 찍은 곳', lat: p.lat, lng: p.lng };
}

function cleanVehicle(v) {
  if (!v || typeof v !== 'object') return null;
  const model = String(v.model || '').trim().slice(0, 30);
  const color = String(v.color || '').trim().slice(0, 15);
  const plate = String(v.plate || '').trim().slice(0, 12); // 번호판 (도착했을 때 차 확인용)
  return model || color ? { model, color, plate } : null;
}

function broadcast(s) {
  const set = watchers.get(s.id);
  if (!set) return;
  const data = `data: ${JSON.stringify(publicView(s))}\n\n`;
  for (const res of set) res.write(data);
}

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve) => {
    let raw = '';
    req.on('data', (c) => (raw += c));
    req.on('end', () => {
      try { resolve(raw ? JSON.parse(raw) : {}); } catch { resolve({}); }
    });
  });
}

// ---------- 휴대폰 알림 (웹 푸시) ----------
// 받는 사람이 링크에서 "알림 켜기"를 누르면, 다른 앱을 보고 있거나 화면이 꺼져 있어도
// 휴대폰 알림창에 "곧 도착해요" 같은 알림이 온다. 언제 보낼지는 서버가 차 위치로 판단한다.
// 표준 웹 푸시(VAPID + aes128gcm 암호화)를 Node 내장 crypto로 직접 구현 (의존성 없음).
// 알림 키는 서버를 켤 때마다 새로 만든다 (픽업도 메모리에만 있어서 서버를 끄면 같이 사라짐).
const vapid = crypto.generateKeyPairSync('ec', { namedCurve: 'prime256v1' });
const vapidPublic = (() => {
  const j = vapid.publicKey.export({ format: 'jwk' });
  return Buffer.concat([Buffer.from([4]), Buffer.from(j.x, 'base64url'), Buffer.from(j.y, 'base64url')]);
})();
// 알림을 보낼 수 있는 곳: 브라우저 회사의 푸시 서버만 (아무 주소로나 요청을 보내지 않게)
const PUSH_HOSTS = [/^fcm\.googleapis\.com$/, /^android\.googleapis\.com$/, /\.push\.apple\.com$/, /\.push\.services\.mozilla\.com$/, /\.notify\.windows\.com$/];
function validSub(sub) {
  try {
    const u = new URL(sub.endpoint);
    const p = Buffer.from(sub.keys.p256dh, 'base64url'), a = Buffer.from(sub.keys.auth, 'base64url');
    return u.protocol === 'https:' && PUSH_HOSTS.some((r) => r.test(u.hostname)) && p.length === 65 && p[0] === 4 && a.length === 16;
  } catch { return false; }
}
function vapidJwt(audience) {
  const enc = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const pub = publicUrl();
  const unsigned = `${enc({ typ: 'JWT', alg: 'ES256' })}.${enc({ aud: audience, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: pub.startsWith('https://') ? pub : 'mailto:push@nowonna.app' })}`;
  const sig = crypto.sign('sha256', Buffer.from(unsigned), { key: vapid.privateKey, dsaEncoding: 'ieee-p1363' });
  return `${unsigned}.${sig.toString('base64url')}`;
}
const hmac = (key, data) => crypto.createHmac('sha256', key).update(data).digest();
// RFC 8291: 받는 브라우저만 풀 수 있게 알림 내용을 암호화
function encryptPush(sub, text) {
  const uaPublic = Buffer.from(sub.keys.p256dh, 'base64url');
  const authSecret = Buffer.from(sub.keys.auth, 'base64url');
  const ecdh = crypto.createECDH('prime256v1');
  const asPublic = ecdh.generateKeys();
  const shared = ecdh.computeSecret(uaPublic);
  const ikm = hmac(hmac(authSecret, shared), Buffer.concat([Buffer.from('WebPush: info\0'), uaPublic, asPublic, Buffer.from([1])]));
  const salt = crypto.randomBytes(16);
  const prk = hmac(salt, ikm);
  const cek = hmac(prk, Buffer.from('Content-Encoding: aes128gcm\0\x01')).subarray(0, 16);
  const nonce = hmac(prk, Buffer.from('Content-Encoding: nonce\0\x01')).subarray(0, 12);
  const c = crypto.createCipheriv('aes-128-gcm', cek, nonce);
  const body = Buffer.concat([c.update(Buffer.concat([Buffer.from(text), Buffer.from([2])])), c.final(), c.getAuthTag()]);
  const head = Buffer.alloc(21);
  salt.copy(head, 0); head.writeUInt32BE(4096, 16); head[20] = asPublic.length;
  return Buffer.concat([head, asPublic, body]);
}
async function sendPush(s, sub, msg) {
  try {
    const r = await fetch(sub.endpoint, {
      method: 'POST',
      headers: {
        Authorization: `vapid t=${vapidJwt(new URL(sub.endpoint).origin)}, k=${vapidPublic.toString('base64url')}`,
        'Content-Encoding': 'aes128gcm', 'Content-Type': 'application/octet-stream',
        TTL: '120', Urgency: 'high', Topic: msg.tag.replace(/[^A-Za-z0-9_-]/g, '').slice(0, 32) || 'nowonna',
      },
      body: encryptPush(sub, JSON.stringify(msg)),
    });
    if (r.status === 404 || r.status === 410) s.push.delete(sub.endpoint); // 알림을 끈 브라우저
    else if (!r.ok) console.log(`[알림] ${s.id} 보내기 실패 ${r.status} ${(await r.text()).slice(0, 120)}`);
  } catch (e) { console.log(`[알림] ${s.id} 보내기 실패: ${e.message}`); }
}

// 웹의 위 배너와 같은 순간·같은 말로 보낸다 (web/index.html checkCarEvents와 맞출 것)
function pushEvents(s) {
  const nimI = s.driverLabel === '운전자' ? '운전자가' : `${s.driverLabel}님이`;
  const nim = s.driverLabel === '운전자' ? '운전자' : `${s.driverLabel}님`;
  const car = s.vehicle ? `${s.vehicle.color || ''} ${s.vehicle.model || ''}`.trim() : '';
  const plate = s.vehicle && s.vehicle.plate ? ` · ${s.vehicle.plate}` : '';
  const placeName = s.place ? s.place.name : '';
  const r = s.car && s.car.remainSec != null ? Math.max(0, s.car.remainSec - (Date.now() - s.car.at) / 1000) : null;
  const driving = s.state === 'DRIVING' && s.place && r != null && s.car.remainSec > 0;
  const arrived = s.state === 'ARRIVED';
  return [
    ['leave', driving && r <= s.leaveSec && r > 60, '지금 나가면 딱 맞아요', `${nim} 차가 ${Math.max(1, Math.ceil((r || 0) / 60))}분 뒤 ${placeName}에 도착해요`],
    ['soon', driving && r <= 60, `${nim} 차가 곧 도착해요`, `1분 안에 ${placeName}에 도착해요`],
    ['arrived', arrived, `${nimI} 도착했어요`, `${car || '차'}${plate} · ${placeName}`],
    ['hazard', arrived && s.hazard, '비상등을 켰어요', `비상등이 깜빡이는 ${car || '차'}${plate}를 찾으세요`],
  ].map(([k, on, title, body]) => ({ key: `${k}:${placeName}`, on, title, body }));
}
// silent = 알림을 켠 순간 이미 지난 일은 보내지 않고 "보낸 것"으로만 표시
function checkPush(s, silent) {
  if (!s.push || !s.push.size || s.state === 'ENDED') return;
  const fired = [];
  for (const e of pushEvents(s)) {
    if (!e.on || s.pushed.has(e.key)) continue;
    s.pushed.add(e.key);
    fired.push(e);
  }
  if (!fired.length || silent) return;
  // 한 번에 여러 개면 가장 중요한(마지막) 것 하나만. 도착+비상등이 같이 오면 "도착" 제목에 비상등 안내를 붙인다
  const last = fired[fired.length - 1];
  const arrived = fired.find((e) => e.key.startsWith('arrived:'));
  if (arrived && last !== arrived) notify(s, arrived.title, last.body);
  else notify(s, last.title, last.body);
}
function notify(s, title, body) {
  if (!s.push || !s.push.size) return;
  console.log(`[알림] ${s.id} → ${title}`);
  const msg = { title, body, tag: `nowonna-${s.id}`, url: `/p/${s.id}` };
  for (const sub of s.push.values()) sendPush(s, sub, msg);
}
// 차 위치 보고 사이에도 남은 시간은 줄어드니 2초마다 확인
setInterval(() => { for (const s of sessions.values()) checkPush(s); }, 2000).unref();

// 장소를 바꿀 때 처음 장소를 기억해 둔다 (웹 "변경됨 · 원래: …")
function setPlace(s, place) {
  if (s.place && !s.originalPlace) s.originalPlace = s.place;
  s.place = place;
}

function validPlace(p) {
  return p && typeof p.lat === 'number' && typeof p.lng === 'number' && !(p.lat === 0 && p.lng === 0);
}

// ---------- TMAP (서버에서만 호출: 앱키를 웹 요청에 싣지 않고, 호출 횟수도 여기서 아낀다) ----------
// 무료 한도(하루): 지오코딩 2만 · 경로안내(자동차+보행자) 1천 → 경로는 장소가 바뀔 때나 90초에 한 번만.
const TMAP = 'https://apis.openapi.sk.com/tmap';

async function tmap(pathAndQuery, body) {
  const r = await fetch(`${TMAP}${pathAndQuery}`, {
    method: body ? 'POST' : 'GET',
    headers: { appKey: TMAP_APP_KEY, Accept: 'application/json', ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined,
    signal: AbortSignal.timeout(5000),
  });
  if (!r.ok) throw new Error(`TMAP ${r.status}`);
  if (r.status === 204) return {}; // 검색 결과 없음은 본문 없이 204로 온다
  return r.json();
}

const routeBody = (from, to) => ({
  startX: from.lng, startY: from.lat, endX: to.lng, endY: to.lat,
  startName: 'start', endName: 'end', reqCoordType: 'WGS84GEO', resCoordType: 'WGS84GEO',
});

// 경로 응답(GeoJSON)에서 총 시간·거리와 선 좌표만 뽑는다
function routeOf(geo) {
  const props = geo.features?.[0]?.properties || {};
  const path = [];
  for (const f of geo.features || []) {
    if (f.geometry?.type === 'LineString') for (const [lng, lat] of f.geometry.coordinates) path.push([lat, lng]);
  }
  return { sec: props.totalTime ?? null, meters: props.totalDistance ?? null, path };
}

// 핀 좌표 → "역삼동 823-1" 같은 짧은 이름 (W3·W6 장소 이름 자동 채우기)
async function addressName(lat, lng) {
  const d = await tmap(`/geo/reversegeocoding?version=1&lat=${lat}&lon=${lng}&coordType=WGS84GEO&addressType=A10`);
  const a = d.addressInfo || {};
  if (a.buildingName) return a.buildingName;
  if (a.roadName) return `${a.roadName} ${a.buildingIndex || ''}`.trim();
  return `${a.legalDong || a.adminDong || ''} ${a.bunji || ''}`.trim() || a.fullAddress || '';
}

// 장소 검색 (TMAP 통합검색) → 가까운 순이 아니라 정확도 순, 내 근처를 기준점으로.
// 좌표는 건물 중심 대신 '입구(front)'를 써서 차가 실제로 설 수 있는 쪽에 핀이 꽂히게 한다.
const searchCache = new Map(); // 같은 검색어를 여러 번 치는 경우가 많아 잠깐 기억 (하루 2만 건 한도)
async function searchPlaces(q, lat, lng) {
  const key = `${q}|${lat?.toFixed(2)},${lng?.toFixed(2)}`;
  const hit = searchCache.get(key);
  if (hit && Date.now() - hit.at < 10 * 60_000) return hit.list;
  const qs = new URLSearchParams({
    version: '1', searchKeyword: q, searchType: 'all', page: '1', count: '7',
    reqCoordType: 'WGS84GEO', resCoordType: 'WGS84GEO', multiPoint: 'N', poiGroupYn: 'N',
  });
  if (lat != null && lng != null) { qs.set('centerLat', lat); qs.set('centerLon', lng); }
  const d = await tmap(`/pois?${qs}`);
  const list = (d.searchPoiInfo?.pois?.poi || []).map((p) => {
    const lt = num(p.frontLat) ?? num(p.noorLat), ln = num(p.frontLon) ?? num(p.noorLon);
    const road = p.newAddressList?.newAddress?.[0]?.fullAddressRoad;
    const addr = road || [p.upperAddrName, p.middleAddrName, p.lowerAddrName, p.detailAddrName, p.firstNo].filter(Boolean).join(' ');
    return { name: p.name, addr, lat: lt, lng: ln, meters: p.radius ? Math.round(Number(p.radius) * 1000) : null };
  }).filter((p) => p.name && p.lat != null && p.lng != null);
  searchCache.set(key, { at: Date.now(), list });
  if (searchCache.size > 200) searchCache.delete(searchCache.keys().next().value);
  return list;
}

// 차 → 만날 곳 자동차 경로 (웹 지도에 그리는 선). 세션마다 캐시.
async function carRoute(s) {
  if (!s.car || !s.place) return null;
  const key = `${s.place.lat},${s.place.lng}`;
  const c = s.routeCache;
  if (c && c.key === key && Date.now() - c.at < 90_000) return c.route;
  const route = routeOf(await tmap('/routes?version=1', { ...routeBody(s.car, s.place), searchOption: '0' }));
  route.path = simplify(route.path, 300);
  s.routeCache = { key, at: Date.now(), route };
  return route;
}

// ---------- 모의 주행 (에뮬레이터 시연용) ----------
// Pleos 에뮬레이터는 내비 위치가 한 곳에 고정돼 차가 움직이지 않는다.
// DEMO_DRIVE=1 로 켜면, 차량 앱이 보낸 첫 위치에서 만날 곳까지 도로를 따라 차가 달린 것처럼
// 서버가 위치·남은 시간·도착을 대신 만든다. 차량 앱은 그대로 두면 된다 (탑승·종료는 앱이 보낸 대로).
const DEMO_DRIVE = process.env.DEMO_DRIVE === '1';
const DEMO_SPEED = Number(process.env.DEMO_SPEED || 12); // m/s (12 = 시속 약 43km)
function distM(a, b) {
  const R = 6371000, r = Math.PI / 180;
  const h = Math.sin((b.lat - a.lat) * r / 2) ** 2 + Math.cos(a.lat * r) * Math.cos(b.lat * r) * Math.sin((b.lng - a.lng) * r / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(h));
}
function bearing(a, b) {
  const r = Math.PI / 180, y = Math.sin((b.lng - a.lng) * r) * Math.cos(b.lat * r);
  const x = Math.cos(a.lat * r) * Math.sin(b.lat * r) - Math.sin(a.lat * r) * Math.cos(b.lat * r) * Math.cos((b.lng - a.lng) * r);
  return (Math.atan2(y, x) / r + 360) % 360;
}
// 출발 후 지난 시간만큼 경로를 따라간 위치
function demoPos(d) {
  const run = Math.min(d.total, DEMO_SPEED * (Date.now() - d.t0) / 1000);
  let i = 1;
  while (i < d.path.length - 1 && d.cum[i] < run) i++;
  const a = d.path[i - 1], b = d.path[i], seg = d.cum[i] - d.cum[i - 1];
  const f = seg > 0 ? Math.min(1, (run - d.cum[i - 1]) / seg) : 1;
  return { lat: a.lat + (b.lat - a.lat) * f, lng: a.lng + (b.lng - a.lng) * f, heading: bearing(a, b), left: d.total - run };
}
async function loadDemoRoute(s, from, key) {
  let road = [];
  try {
    if (TMAP_APP_KEY) road = routeOf(await tmap('/routes?version=1', { ...routeBody(from, s.place), searchOption: '0' })).path.map(([lat, lng]) => ({ lat, lng }));
  } catch (e) { console.log(`[모의 주행] ${s.id} 도로 경로 실패, 직선으로: ${e.message}`); }
  if (!s.demo || s.demo.key !== key || !s.place) return; // 그사이 장소가 또 바뀜
  const path = [from, ...road, { lat: s.place.lat, lng: s.place.lng }];
  const cum = [0];
  for (let i = 1; i < path.length; i++) cum.push(cum[i - 1] + distM(path[i - 1], path[i]));
  s.demo = { key, path, cum, total: cum[cum.length - 1], t0: Date.now() };
  console.log(`[모의 주행] ${s.id} → ${s.place.name} ${Math.round(s.demo.total)}m · 약 ${Math.max(1, Math.round(s.demo.total / DEMO_SPEED / 60))}분`);
}
// 차량 앱이 보낸 값 위에 모의 위치·남은 시간·상태를 덮어쓴다
function demoApply(s) {
  if (!DEMO_DRIVE || !s.place || !s.car || s.state === 'BOARDED' || s.state === 'ENDED') return false;
  const key = `${s.place.lat},${s.place.lng}`;
  if (!s.demo || s.demo.key !== key) {
    // 처음이면 앱이 보낸 위치에서, 장소가 바뀌면 지금 달리던 자리에서 다시 출발
    const from = s.demo && s.demo.path ? demoPos(s.demo) : { lat: s.car.lat, lng: s.car.lng };
    s.demo = { key, path: null, from: { lat: from.lat, lng: from.lng } };
    loadDemoRoute(s, s.demo.from, key);
  }
  const d = s.demo;
  const p = d.path ? demoPos(d) : { ...d.from, heading: s.car.heading || 0, left: null };
  s.car = { lat: p.lat, lng: p.lng, heading: Math.round(p.heading), remainSec: p.left == null ? null : Math.round(p.left / DEMO_SPEED), remainMeters: p.left == null ? null : Math.round(p.left), at: Date.now() };
  s.state = p.left != null && p.left < 3 ? 'ARRIVED' : 'DRIVING';
  return true;
}
// 차량 앱은 5초마다 보내지만, 웹에서 부드럽게 보이게 1초마다 움직인다
if (DEMO_DRIVE) setInterval(() => {
  for (const s of sessions.values()) {
    if (!s.demo || s.state === 'ENDED' || s.state === 'BOARDED') continue;
    const was = s.state;
    if (!demoApply(s)) continue;
    if (was === 'DRIVING' || s.state === 'DRIVING') { broadcast(s); checkPush(s); }
  }
}, 1000).unref();

// 선 좌표가 너무 많으면 고르게 덜어낸다 (웹으로 보내는 양 줄이기)
function simplify(path, max) {
  if (path.length <= max) return path;
  const step = path.length / max;
  const out = [];
  for (let i = 0; i < max; i++) out.push(path[Math.floor(i * step)]);
  out.push(path[path.length - 1]);
  return out;
}

const num = (v) => (v == null || v === '' || !Number.isFinite(Number(v)) ? null : Number(v));

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`);
  const parts = url.pathname.split('/').filter(Boolean);

  if (req.method === 'OPTIONS') {
    res.writeHead(204, { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': 'Content-Type, Authorization', 'Access-Control-Allow-Methods': 'GET,POST' });
    return res.end();
  }

  // ---------- 개발용 첫 화면: 진행 중인 픽업 목록 ----------
  if (req.method === 'GET' && parts.length === 0) {
    const live = [...sessions.values()].filter((x) => x.state !== 'ENDED').sort((a, b) => b.createdAt - a.createdAt);
    const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
    const rows = live.map((x) => `<li><a href="/p/${x.id}">${esc(x.contactName || '받는 사람')} · ${x.permission} · ${esc(x.place ? x.place.name : '장소 미정')}</a> <small>${x.state}</small></li>`).join('');
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(`<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="3"><title>나온나 server-lite</title>
<body style="font-family:-apple-system,sans-serif;background:#F3F4F6;color:#11151B;padding:32px;line-height:1.9">
<h2>나온나 · 진행 중인 픽업 <small style="color:#8A94A3;font-weight:400">(개발용)</small></h2>
${rows ? `<ul>${rows}</ul>` : '<p>진행 중인 픽업이 없어요.<br>차량 앱에서 픽업을 시작하거나 <code>node sim-car.js</code>를 실행하면 여기에 나타나요.</p>'}
<style>a{color:#11151B;font-weight:600}small{color:#8A94A3;margin-left:8px}</style></body>`);
  }

  // ---------- 받는 사람 웹 ----------
  // 지도 SDK는 여기서 끼워 넣는다: 앱키가 있으면 TMAP, 개발용 Leaflet(OpenStreetMap)은 항상 예비로
  // (앱키가 틀려 TMAP이 안 뜨면 웹이 알아서 Leaflet 지도로 열려요)
  // 홈 화면에 추가하면 이 픽업 화면이 앱처럼 열리게 (아이폰은 이렇게 열어야 휴대폰 알림을 받을 수 있다)
  if (req.method === 'GET' && parts[0] === 'p' && parts[1] && parts[2] === 'manifest.webmanifest') {
    res.writeHead(200, { 'Content-Type': 'application/manifest+json; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(JSON.stringify({ name: '나온나', short_name: '나온나', start_url: `/p/${encodeURIComponent(parts[1])}`, scope: '/', display: 'standalone', background_color: '#F3F4F6', theme_color: '#F3F4F6' }));
  }
  if (req.method === 'GET' && parts[0] === 'p' && parts[1]) {
    const manifest = `<link rel="manifest" href="/p/${encodeURIComponent(parts[1])}/manifest.webmanifest">\n<meta name="apple-mobile-web-app-capable" content="yes">\n`;
    const leaflet = '<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">\n<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>';
    const sdk = (TMAP_APP_KEY ? `<script src="https://apis.openapi.sk.com/tmap/jsv2?version=1&appKey=${encodeURIComponent(TMAP_APP_KEY)}"></script>\n` : '') + leaflet;
    const html = fs.readFileSync(path.join(__dirname, 'web', 'index.html'), 'utf8').replace('<!--MAP_SDK-->', manifest + sdk);
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Cache-Control': 'no-store' });
    return res.end(html);
  }

  // ---------- 알림 받는 서비스 워커 (휴대폰 알림창에 띄우기) ----------
  if (req.method === 'GET' && parts[0] === 'sw.js' && parts.length === 1) {
    res.writeHead(200, { 'Content-Type': 'text/javascript; charset=utf-8', 'Cache-Control': 'no-cache' });
    return res.end(fs.readFileSync(path.join(__dirname, 'web', 'sw.js')));
  }

  // ---------- 개인정보 처리방침 ----------
  if (req.method === 'GET' && parts[0] === 'privacy' && parts.length === 1) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    return res.end(fs.readFileSync(path.join(__dirname, 'web', 'privacy.html')));
  }

  if (parts[0] !== 'api' || parts[1] !== 'sessions') return json(res, 404, { error: 'not found' });
  const id = parts[2];
  const action = parts[3];

  // POST /api/sessions — 차량: 픽업 시작
  if (req.method === 'POST' && !id) {
    const b = await readBody(req);
    const s = {
      id: newId(),
      driverLabel: String(b.driverLabel || '').trim().slice(0, 20) || '운전자',
      contactName: b.contactName || '',
      contactPhone: b.contactPhone || '', // 문자 발송용 (웹에는 안 보냄)
      permission: ['VIEW', 'SUGGEST', 'AUTO'].includes(b.permission) ? b.permission : 'VIEW',
      place: validPlace(b.place) ? cleanPlace(b.place) : null, // null이면 받는 사람이 정한다
      originalPlace: null,
      notifyBeforeSec: Number(b.notifyBeforeSec) > 0 ? Number(b.notifyBeforeSec) : 180,
      vehicle: cleanVehicle(b.vehicle),
      hazard: false,
      car: null,
      state: 'DRIVING', // DRIVING | ARRIVED | BOARDED | ENDED
      pendingChange: null,
      lastResolution: null,
      nextPlace: null,
      push: new Map(), // 알림 받을 브라우저 (endpoint → 구독 정보, 웹에는 안 보냄)
      pushed: new Set(), // 이미 보낸 알림
      leaveSec: Number(b.notifyBeforeSec) > 0 ? Number(b.notifyBeforeSec) : 180, // "지금 나가면 딱 맞아요" 시점 (웹이 걸어서 걸리는 시간으로 갱신)
      viewedAt: null,
      boardedAt: null,
      endedAt: null,
      createdAt: Date.now(),
      expiresAt: Date.now() + LINK_TTL_MS,
      driverToken: newDriverToken(), // 차량에게만 응답으로 준다 (웹·링크에는 절대 안 보냄)
    };
    sessions.set(s.id, s);
    const link = `${publicUrl()}/p/${s.id}`;
    console.log(`[세션] ${s.id} (${s.permission}) ${link}`); // 운영 기록엔 이름·전화번호를 남기지 않는다
    // TODO(실제 서버): 여기서 contactPhone으로 문자 발송
    return json(res, 200, { id: s.id, link, driverToken: s.driverToken, expiresAt: s.expiresAt });
  }

  const s = sessions.get(id);
  if (!s) return json(res, 404, { error: 'session not found' });
  if (s.state !== 'ENDED' && Date.now() > s.expiresAt) endSession(s, '(2시간 만료)');

  // 차량 전용 요청은 비밀키가 맞아야 한다 (틀리면 401, 상태는 그대로)
  if (req.method === 'POST' && DRIVER_ACTIONS.has(action) && !isDriver(req, s)) {
    console.log(`[거절] ${s.id} ${action}: 차량 비밀키 없음/불일치`);
    return json(res, 401, { error: 'driver token required' });
  }

  // GET /api/sessions/:id — 웹: 현재 상태 (처음 열 때 = 링크 확인)
  if (req.method === 'GET' && !action) {
    if (!s.viewedAt && url.searchParams.get('viewer') === 'web') { s.viewedAt = Date.now(); broadcast(s); }
    return json(res, 200, publicView(s));
  }

  // GET /api/sessions/:id/stream — 웹: 실시간 구독 (Server-Sent Events)
  if (req.method === 'GET' && action === 'stream') {
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive', 'Access-Control-Allow-Origin': '*' });
    res.write(`data: ${JSON.stringify(publicView(s))}\n\n`);
    if (!watchers.has(id)) watchers.set(id, new Set());
    watchers.get(id).add(res);
    // 배포 환경의 프록시가 조용한 연결을 끊지 않게 15초마다 빈 신호
    const ping = setInterval(() => res.write(': ping\n\n'), 15000);
    req.on('close', () => { clearInterval(ping); watchers.get(id)?.delete(res); });
    return;
  }

  // ---------- 웹 → TMAP (진행 중인 픽업 링크에서만) ----------
  if (req.method === 'GET' && ['address', 'walk', 'route', 'search'].includes(action)) {
    if (s.state === 'ENDED') return json(res, 410, { error: 'ended' });
    if (!TMAP_APP_KEY) return json(res, 503, { error: 'no tmap key' });
    const lat = num(url.searchParams.get('lat')), lng = num(url.searchParams.get('lng'));
    try {
      // GET /api/sessions/:id/address?lat&lng → { name }  핀 주소
      if (action === 'address') {
        if (lat == null || lng == null) return json(res, 400, { error: 'lat,lng' });
        return json(res, 200, { name: await addressName(lat, lng) });
      }
      // GET /api/sessions/:id/search?q&lat&lng → { places:[{name,addr,lat,lng,meters}] }  장소 검색
      if (action === 'search') {
        const q = (url.searchParams.get('q') || '').trim().slice(0, 40);
        if (q.length < 2) return json(res, 200, { places: [] });
        return json(res, 200, { places: await searchPlaces(q, lat, lng) });
      }
      // GET /api/sessions/:id/walk?lat&lng → { sec, meters }  내 위치 → 만날 곳 걸어서 (W2)
      if (action === 'walk') {
        if (lat == null || lng == null || !s.place) return json(res, 400, { error: 'lat,lng,place' });
        const key = `${lat.toFixed(4)},${lng.toFixed(4)}>${s.place.lat},${s.place.lng}`;
        if (s.walkCache?.key !== key) {
          const w = routeOf(await tmap('/routes/pedestrian?version=1', routeBody({ lat, lng }, s.place)));
          s.walkCache = { key, value: { sec: w.sec, meters: w.meters } };
        }
        return json(res, 200, s.walkCache.value);
      }
      // GET /api/sessions/:id/route → { sec, meters, path:[[lat,lng]…] }  차 → 만날 곳 도로 경로
      if (action === 'route') return json(res, 200, (await carRoute(s)) || { path: [] });
    } catch (e) {
      console.log(`[TMAP] ${action} 실패: ${e.message}`);
      return json(res, 502, { error: 'tmap' });
    }
  }

  // ---------- 휴대폰 알림 ----------
  // GET  /api/sessions/:id/push → { key }  알림 구독에 쓸 서버 공개키
  // POST /api/sessions/:id/push { sub, leaveSec }  알림 켜기 / "나갈 타이밍" 갱신 · { sub, off: true } 끄기
  if (action === 'push' && req.method === 'GET') return json(res, 200, { key: vapidPublic.toString('base64url') });
  if (action === 'push' && req.method === 'POST') {
    if (s.state === 'ENDED') return json(res, 410, { error: 'ended' });
    const b = await readBody(req);
    if (!b.sub || !validSub(b.sub)) return json(res, 400, { error: 'invalid subscription' });
    if (b.off) { s.push.delete(b.sub.endpoint); return json(res, 200, { ok: true }); }
    const isNew = !s.push.has(b.sub.endpoint);
    if (isNew && s.push.size >= 5) return json(res, 429, { error: 'too many' });
    s.push.set(b.sub.endpoint, { endpoint: b.sub.endpoint, keys: { p256dh: b.sub.keys.p256dh, auth: b.sub.keys.auth } });
    const leave = num(b.leaveSec);
    // 받는 사람이 웹에서 알림 시점을 고를 수 있어서 운전자 설정보다 짧아도 된다 (최소 30초)
    if (leave != null) s.leaveSec = Math.min(3600, Math.max(30, leave));
    if (isNew) { checkPush(s, true); console.log(`[알림] ${s.id} 알림 켬 (${s.push.size}대)`); }
    return json(res, 200, { ok: true });
  }

  if (req.method === 'POST' && s.state === 'ENDED' && action !== 'end') return json(res, 410, { error: 'ended' });

  // POST /api/sessions/:id/location — 차량: 5초마다 위치·남은 시간·상태·비상등
  // 응답으로 받는 사람의 변경 요청(pendingChange), 링크 확인 여부, 다음 목적지를 돌려준다
  if (req.method === 'POST' && action === 'location') {
    const b = await readBody(req);
    s.car = { lat: b.lat, lng: b.lng, heading: b.heading || 0, remainSec: b.remainSec ?? null, remainMeters: b.remainMeters ?? null, at: Date.now() };
    s.hazard = b.hazard === true;
    if (b.state && ['DRIVING', 'ARRIVED', 'BOARDED'].includes(b.state)) s.state = b.state;
    if (s.state === 'BOARDED' && !s.boardedAt) s.boardedAt = Date.now();
    demoApply(s); // DEMO_DRIVE=1 일 때만: 고정 위치 대신 도로를 달리는 모의 위치
    broadcast(s);
    checkPush(s);
    return json(res, 200, { pendingChange: s.pendingChange, viewedAt: s.viewedAt, place: s.place, nextPlace: s.nextPlace });
  }

  // POST /api/sessions/:id/change — 웹: 장소 변경 (권한에 따라 요청/제안/바로 반영)
  if (req.method === 'POST' && action === 'change') {
    const b = await readBody(req);
    if (!validPlace(b.place)) return json(res, 400, { error: 'invalid place' });
    const change = { place: cleanPlace(b.place), message: String(b.message || '').slice(0, 60), at: Date.now() };
    // 받는 사람이 처음 정하는 경우와 자동 반영 권한은 바로 반영
    if (!s.place || s.permission === 'AUTO') {
      const first = !s.place;
      setPlace(s, change.place);
      change.applied = true;
      s.lastResolution = { accepted: true, auto: true, first, place: change.place, at: change.at };
    }
    s.pendingChange = change;
    console.log(`[변경] ${s.id} ${change.applied ? '반영' : '요청'} → ${change.place.name}${change.message ? " (한마디 있음)" : ""}`);
    broadcast(s);
    return json(res, 200, { ok: true, applied: !!change.applied });
  }

  // POST /api/sessions/:id/resolve — 차량: 변경 요청 승인/유지 결과
  if (req.method === 'POST' && action === 'resolve') {
    const b = await readBody(req);
    const pc = s.pendingChange;
    if (pc) {
      if (!pc.applied) {
        if (b.accepted) setPlace(s, pc.place);
        s.lastResolution = { accepted: !!b.accepted, auto: false, place: pc.place, at: Date.now() };
        console.log(`[답] ${s.id} ${b.accepted ? '승인' : '유지'} → ${pc.place.name}`);
        const who = s.driverLabel === '운전자' ? '운전자가' : `${s.driverLabel}님이`;
        notify(s, b.accepted ? `${who} 변경을 수락했어요` : `${who} 원래 장소를 유지했어요`, `${s.place ? s.place.name : '처음 장소'}에서 만나요`);
      }
      s.pendingChange = null;
      broadcast(s);
    }
    return json(res, 200, { ok: true, place: s.place });
  }

  // POST /api/sessions/:id/next — 웹: 탑승 후 목적지 남기기 / 지우기 { place: {name,lat,lng} | null }
  if (req.method === 'POST' && action === 'next') {
    const b = await readBody(req);
    if (b.place !== null && !validPlace(b.place)) return json(res, 400, { error: 'invalid place' });
    s.nextPlace = b.place ? cleanPlace(b.place) : null;
    console.log(`[다음 목적지] ${s.id} → ${s.nextPlace ? s.nextPlace.name : '지움'}`);
    broadcast(s);
    return json(res, 200, { ok: true, nextPlace: s.nextPlace });
  }

  // POST /api/sessions/:id/end — 차량: 공유 종료 (탑승·직접 종료) → 링크 만료, 위치·전화번호 삭제
  if (req.method === 'POST' && action === 'end') {
    endSession(s, s.boardedAt ? '(탑승)' : '(운전자 종료)');
    return json(res, 200, { ok: true });
  }

  return json(res, 404, { error: 'not found' });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`나온나 server-lite: http://localhost:${PORT}  · 에뮬레이터 http://10.0.2.2:${PORT}  · 링크 ${publicUrl()}`);
  if (DEMO_DRIVE) console.log(`모의 주행 켜짐: 차량 앱이 보낸 위치에서 시속 ${Math.round(DEMO_SPEED * 3.6)}km로 만날 곳까지 달려요`);
  console.log(TMAP_APP_KEY ? '지도: TMAP (.env 앱키 사용)' : '지도: 개발용 OpenStreetMap (TMAP 쓰려면 .env 에 TMAP_APP_KEY)');
});
