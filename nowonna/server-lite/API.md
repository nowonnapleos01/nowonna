# 나온나 server-lite API

차량 앱과 받는 사람 웹이 주고받는 형식이에요. 실제 서버(문자 발송, DB, 인증)는 이 형식을 그대로 맞춰 주세요.

실행: `node server.js` (포트 8787). 에뮬레이터에서는 `http://10.0.2.2:8787`, 같은 와이파이 휴대폰에서는 서버가 출력하는 링크 주소로 접속해요.

## 차량 앱 → 서버
| 요청 | 본문 | 응답 |
|---|---|---|
| `POST /api/sessions` 픽업 시작 | `{ driverLabel, contactName, contactPhone, permission: "VIEW"\|"SUGGEST"\|"AUTO", place: {name,lat,lng} \| null, notifyBeforeSec }` | `{ id, link, driverToken, expiresAt }` → link를 받는 사람에게 보냄 |
| `POST /api/sessions/:id/location` 5초마다 | `{ lat, lng, heading, remainSec, remainMeters, state: "DRIVING"\|"ARRIVED"\|"BOARDED" }` | `{ pendingChange, viewedAt, place }` |
| `POST /api/sessions/:id/resolve` 변경 요청 답 | `{ accepted: true\|false }` | `{ ok, place }` |
| `POST /api/sessions/:id/end` 종료 | – | `{ ok }` → 링크 만료, 위치 삭제 |

- `place: null`이면 받는 사람이 링크에서 정해요.
- `pendingChange`: `{ place, message, at, applied }`. `applied: true`면 이미 반영된 것(자동 반영 권한이나 처음 정하기)이라 차량은 내비 목적지만 바꾸면 돼요. `false`면 운전자 승인 카드(C3)를 띄우고 결과를 `resolve`로 보내요.
- `viewedAt`: 받는 사람이 링크를 처음 연 시각 (C2의 "링크 확인" 단계).

### 차량 앱이 추가로 보내고 받는 값 (11/3 차량 → 11/5 서버 반영)
아래 값은 서버가 모두 받아서 웹에 보여줘요.

| 어디 | 값 | 설명 |
|---|---|---|
| `POST /api/sessions` 본문 | `driverLabel` | 운전자가 등록한 이름 (없으면 "운전자") |
| | `notifyBeforeSec` | 나갈 타이밍 알림 시점: 60 / 180 / 300 / 600 |
| | `vehicle: { model, color } \| null` | 차종·색상 (W5 차 찾기). ⚠️ 서버의 `car`(위치)와 이름이 겹치지 않게 `vehicle` |
| `POST /api/sessions` 응답 | `driverToken` | 차량 전용 비밀키 (43글자 무작위). 차량 앱이 이후 요청에 `Authorization: Bearer <driverToken>` 헤더로 붙여요 |
| `location` 본문 | `hazard: boolean` | 비상등 켜짐 (계기판 경고등의 좌·우 방향지시등이 동시에 켜짐) |
| `location` 응답 | `nextPlace: { name, lat, lng, address? } \| null` | 받는 사람이 웹에서 남긴 다음 목적지 → 탑승 후 차량 C5에서 바로 안내 |
| `location` 응답 코드 | `410` 또는 `404` | 링크 만료·종료된 픽업 → 차량 앱이 공유를 끝내요 (S2 만료와 연결) |

- 탑승: 차량 앱은 조수석·뒷문이 **열렸다가 닫히면** 탑승으로 보고, `state: "BOARDED"`를 한 번 보낸 뒤 바로 `end`를 불러요.
- 도착: `state: "ARRIVED"` = 기어 P + 남은 거리 50m 이내, 또는 내비 도착 이벤트.
- 서버 주소: 차량 앱 `car-app/gradle.properties`의 `nowonna.serverUrl`에 배포 주소를 넣으면 돼요. https면 평문 통신은 자동으로 막혀요.

### 보안 (11/7)
- **차량 전용 요청** `location` · `resolve` · `end`는 `Authorization: Bearer <driverToken>`이 맞아야 받아요. 없거나 틀리면 `401`이고 상태는 바뀌지 않아요.
  링크 id는 받는 사람에게 가는 값이라 누구나 알 수 있어서, 링크만으로 차 위치를 조작하거나 공유를 끝내지 못하게 하려는 거예요.
- `driverToken`은 픽업마다 새로 만들고, 차량에게 주는 응답에만 들어가요. 웹이 받는 상태(`GET`·`stream`)와 링크에는 들어가지 않아요.
- **링크 자동 만료**: 시작 후 2시간(`expiresAt`)이 지나면 공유를 끝내요 (`end`와 같음: 위치·전화번호 삭제, 이후 `410`). 끝난 픽업은 하루 뒤 메모리에서 지워져 `404`가 돼요.
  테스트할 땐 `LINK_TTL_MIN=1 node server.js`처럼 분 단위로 줄일 수 있어요.

## 받는 사람 웹 → 서버
| 요청 | 설명 |
|---|---|
| `GET /p/:id` | 받는 사람 웹 페이지 (처음 열 때 위치 공유 안내). `id`는 16글자 무작위 |
| `GET /privacy` | 개인정보 처리방침 |
| `GET /api/sessions/:id?viewer=web` | 현재 상태. 처음 호출하면 `viewedAt` 기록 |
| `GET /api/sessions/:id/stream` | 실시간 구독 (Server-Sent Events). 상태가 바뀔 때마다 전체 상태를 보냄 |
| `POST /api/sessions/:id/change` | `{ place: {name,lat,lng}, message }` 장소 변경 요청·제안·반영 |
| `POST /api/sessions/:id/next` | `{ place: {name,lat,lng} \| null }` 탑승 후 목적지 남기기 / 지우기 → 차량은 `location` 응답의 `nextPlace`로 받아요 |

### TMAP (11/5, 서버가 대신 호출 · 진행 중인 픽업 링크에서만 · 끝난 픽업은 410)
앱키는 서버의 `.env`(`TMAP_APP_KEY`)에만 있어요. 키가 없으면 503을 돌려주고, 웹은 개발용 지도로 열려요.

| 요청 | 응답 | 쓰는 곳 |
|---|---|---|
| `GET /api/sessions/:id/address?lat&lng` | `{ name }` 핀 자리 건물·도로명 (리버스 지오코딩) | W3·W6 장소 이름 자동 채우기 |
| `GET /api/sessions/:id/walk?lat&lng` | `{ sec, meters }` 내 위치 → 만날 곳 걸어서 (보행자 경로) | W2 나갈 타이밍 = 차 남은 시간 ≤ 걸어서 + 30초 |
| `GET /api/sessions/:id/route` | `{ sec, meters, path: [[lat,lng]…] }` 차 → 만날 곳 도로 경로 (최대 300점) | W1 지도의 경로선 |
| `GET /api/sessions/:id/search?q&lat&lng` | `{ places: [{ name, addr, lat, lng, meters }] }` 장소 검색 (통합검색, 최대 7개). 좌표는 건물 **입구**, `meters`는 기준점(내 위치)에서 거리 | W3·W6·탑승 후 목적지의 검색창 |

- 무료 한도(하루): 지오코딩·장소 검색 각 2만, 경로안내(자동차+보행자) 1천. 장소 검색은 같은 검색어를 10분간 서버가 기억해요. 그래서 경로는 장소가 바뀔 때나 90초에 한 번만 다시 받고(서버 캐시), 웹은 받아 둔 경로를 차 위치에서부터 잘라서 그려요.

웹이 받는 상태(`GET`·`stream`)에 11/5부터 들어간 값:

| 값 | 설명 |
|---|---|
| `vehicle`, `hazard` | 차 찾기 화면(W5): "비상등 켠 흰색 아이오닉 5가 저예요" |
| `lastResolution` | `{ accepted, auto, first?, place, at }` 운전자의 마지막 답 → 웹 위쪽 알림(W4) |
| `originalPlace` | 바뀌기 전 처음 장소 → "변경됨 · 원래: …" |
| `nextPlace` | 탑승 후 목적지 (W7) |
| `boardedAt` | 탑승 시각 → 종료 화면이 "탑승했어요"로 |
| `expiresAt` | 링크 자동 만료 시각 (시작 후 2시간) |
| `now` | 서버 시각. 웹이 "마지막 위치 ○초 전"을 계산할 때 시계 차이 보정 |

- 끝난 픽업(`ENDED`)에 `location`·`change`·`next`를 보내면 `410`을 돌려줘요. 차량 앱은 410을 받으면 공유를 끝내요.
- `end` 때 차 위치·비상등·받는 사람 전화번호를 지워요.
- `GET /` 는 개발용 진행 중인 픽업 목록이에요.

### 휴대폰 알림 (11/9, 웹 푸시)
받는 사람이 링크에서 "휴대폰 알림 → 켜기"를 누르면, 다른 앱을 보거나 화면이 꺼져 있어도 휴대폰 알림창에 알림이 와요. 보내는 시점은 서버가 `location`으로 받은 남은 시간을 보고 정해요 (차량 앱은 바꿀 것 없음).

| 요청 | 설명 |
|---|---|
| `GET /api/sessions/:id/push` | `{ key }` 알림 구독용 서버 공개키 (VAPID) |
| `POST /api/sessions/:id/push` | `{ sub, leaveSec }` 알림 켜기 · "나갈 타이밍" 갱신 / `{ sub, off: true }` 끄기 |
| `GET /sw.js` | 알림을 띄우는 서비스 워커 |

- 보내는 알림: 나갈 타이밍(`leaveSec` = 걸어서 걸리는 시간+30초와 알림 시점 중 큰 값) → 곧 도착(1분 이내) → 도착(+비상등) → 운전자의 변경 수락/유지. 화면 안 배너와 같은 문구예요.
- https 주소에서만 돼요. 아이폰은 사파리 공유 → 홈 화면에 추가한 뒤 그 아이콘으로 열어야 받을 수 있어요 (iOS 16.4+).
- 알림 키는 서버를 켤 때마다 새로 만들어요. 공유가 끝나면 구독 정보도 지워요.

### 에뮬레이터 모의 주행 (11/9, 개발용)
Pleos 에뮬레이터는 내비 위치가 고정이라 차가 안 움직여요. 서버를 `DEMO_DRIVE=1 node server.js`로 켜면 차량 앱이 처음 보낸 위치에서 만날 곳까지 TMAP 도로 경로를 따라 서버가 차를 움직여요 (`DEMO_SPEED` m/s, 기본 12 ≈ 시속 43km). 남은 시간·도착(`ARRIVED`)·휴대폰 알림도 이 위치로 계산하고, 장소가 바뀌면 달리던 자리에서 다시 출발해요. 탑승(`BOARDED`)·종료는 앱이 보낸 대로예요. 실제 차에서는 켜지 마세요.

## 실제 서버에서 추가할 것
- 세션 생성 시 `contactPhone`으로 문자 발송 (지금은 콘솔에 링크만 출력)
- 나갈 타이밍 알림, 도착 알림 문자 (웹 푸시를 안 켠 사람·아이폰 사파리용. 시연에서는 링크를 직접 전달)
- 알림 키(VAPID)를 고정값으로 저장 (DB로 바꾸면 서버를 다시 켜도 구독이 살아 있게)
- 메모리 대신 DB (지금은 서버를 다시 켜면 진행 중인 픽업이 사라져요)
