# 나온나 구조

코드를 처음 읽는 분을 위한 안내예요. 기능 소개와 실행 방법은 [README](README.md), 서버와 주고받는 형식은 [API.md](nowonna/server-lite/API.md)에 있어요.

## 1. 세 부분

```
차량 앱 (Kotlin)  ──위치·남은 시간──▶  server-lite (Node)  ──SSE──▶  받는 사람 웹 (HTML·JS)
 Pleos SDK 사용     ◀──장소 변경 요청──   메모리 저장·TMAP 대행   ◀──장소 변경──   앱 설치 없이 링크로
```

| 부분 | 위치 | 한 줄 요약 |
|---|---|---|
| 차량 앱 | `nowonna/car-app/app/src/main/java/ai/nowonna/car/` | 차량 신호를 읽어 픽업을 진행하고 서버에 알린다. 길 안내는 Pleos 내비가 한다 |
| 서버 | `nowonna/server-lite/server.js` | 픽업 1건 = 세션 1개. 차와 웹 사이를 잇고, TMAP 호출과 웹 푸시를 맡는다. 외부 라이브러리 없음 |
| 받는 사람 웹 | `nowonna/server-lite/web/index.html` | 한 파일짜리 웹. 지도·상태 그리기·장소 고르기·알림 |

## 2. 픽업 한 건의 흐름

1. **시작:** 차량 C1에서 [공유하고 출발] → `Pickup.start()` → `POST /api/sessions`. 서버가 링크(`/p/:id`, 16자리 무작위)와 차량 전용 키(`driverToken`)를 돌려준다. 장소가 있으면 `CarSignals.requestRoute()`로 내비 안내를 건다.
2. **주행:** `Pickup.startSync()`가 5초마다 위치·남은 시간을 `POST /location`으로 보낸다. 서버는 바뀐 상태를 SSE(`/stream`)로 웹에 밀어 준다. SSE가 막히는 망에서는 웹이 2초마다 직접 묻는다.
3. **장소 변경:** 웹 `POST /change` → 다음 `location` 응답에 `pendingChange`가 실려 차로 간다 → `Pickup.receive()`.
   - 보기·제안 권한: 내비 위 카드로 [이번만 변경]/[유지]를 묻는다. 10초 무응답이면 유지한다.
   - 자동 반영 권한: 바로 경로를 바꾼다.
   - 답은 `POST /resolve`로 서버에 전하고, 웹에 "변경됨"으로 보인다.
4. **도착:** 내비 도착 이벤트, 또는 기어 P와 남은 거리 50m 이내 → `CarSignals.markArrived()` → 상태 `ARRIVED`. 계기판 좌·우 방향지시등이 함께 켜지면 비상등으로 보고 웹에 알린다.
5. **탑승:** 도착 뒤 조수석·뒷문이 **열렸다 닫히면** 탑승(`CarSignals.onDoor`) → `Pickup.onBoarded()`가 `BOARDED`를 한 번 보내고 `POST /end`. 서버는 차 위치·전화번호를 즉시 지우고 링크를 만료한다.
6. **다음 목적지:** 받는 사람이 남긴 탑승 후 목적지가 있으면 C5에서 묻고, [안내 시작]이면 내비로 바로 넘어간다.

세션 상태: `DRIVING → ARRIVED → BOARDED → ENDED`. 시작 후 2시간이 지나도 `ENDED`가 되고, 끝난 세션은 24시간 뒤 메모리에서도 지운다.

## 3. 차량 앱 파일

| 파일 | 맡은 일 | 먼저 볼 곳 |
|---|---|---|
| `CarSignals.kt` | Pleos **Vehicle SDK**(기어·문·계기판 경고등)와 **NaviHelper SDK**(위치·남은 시간·경로 요청·도착 이벤트)를 한곳에서 다루고, 화면이 읽는 상태로 바꾼다 | `startVehicle()`, `naviListener`, `onDoor()`, `onTelltales()` |
| `Pickup.kt` | 픽업 1건의 상태와 서버 동기화. 화면과 분리돼 있어 내비가 앞에 있어도 돈다 | `start()`, `startSync()`, `receive()`/`accept()`/`keep()`, `onBoarded()` |
| `PickupService.kt` | 공유 중에만 켜지는 포그라운드 서비스 (뒤에 있어도 앱이 멈추지 않게) | |
| `ServerClient.kt` | 서버 통신. `driverToken`을 차량 전용 요청에 붙인다 | |
| `Phone.kt` | 차에 연결된 휴대폰의 연락처·최근 통화, 통화 종료 감지 (C6) | |
| `Profile.kt` | 운전자·차 정보 (이름·차종·색상·번호판·알림 시점) | |
| `C1`~`C6` 화면, `MainActivity.kt` | Jetpack Compose 화면. 상태를 그리기만 하고 판단은 위 파일들이 한다 | |

**설계 원칙**
- **운전 중 조작 최소화:** 주행 중에는 큰 버튼 한 번 탭만 받는다. 설정은 기어 P가 확인될 때만 열린다.
- **차량은 읽기만:** 차량을 제어하지 않는다 (`CONTROL_*` 권한 없음).
- **시연용 코드는 분리:** "모의 주행", "도착/탑승 시연" 같은 시연 버튼은 `BuildConfig.DEV_TOOLS`가 켜진 debug 빌드에서만 보인다.

## 4. 서버 (`server.js`)

위에서 아래로 이렇게 이어져 있다.
1. 설정 (`.env`)
2. 보안 (링크 id, `driverToken`, 만료 정리)
3. 휴대폰 알림 (웹 푸시: VAPID 서명 + aes128gcm 암호화를 Node `crypto`로 직접 구현)
4. TMAP 호출 (자동차·도보 경로, 주소, 장소 검색. 앱키는 서버에만 두고 결과는 캐시)
5. 모의 주행 (`DEMO_DRIVE=1`)
6. HTTP 라우팅

- 데이터는 **메모리에만** 둔다. 실서비스에서는 DB와 문자 발송을 붙이면 되고, API 형식은 그대로 쓴다.
- 받는 사람 웹에 보내는 값은 `publicView()`에서 고른다. 전화번호와 `driverToken`은 웹에 나가지 않는다.

## 5. 받는 사람 웹 (`web/index.html`)

한 파일 안에 아래 순서로 구역이 나뉘어 있다.
- 지도: `MapView`. TMAP을 쓰고, 앱키가 없으면 Leaflet
- 상태: `s` = 서버가 보내는 세션
- 그리기: `render()`
- 장소 고르기와 검색
- 휴대폰 알림
- 배너 알림 (`checkCarEvents`)
- 서버 연결 (SSE + 폴링 대비)

**지도에서 신경 쓴 점**
- 마커·경로선은 한 번 만들고 바뀐 것만 고친다. 매번 새로 만들면 확대·축소가 끊긴다.
- 트랙패드 핀치는 휠 신호를 모아 한 단계씩 확대한다. TMAP이 잘게 쪼갠 신호를 몰아 처리해서 끊기기 때문이다.

## 6. 개발 도구

| 도구 | 용도 |
|---|---|
| `sim-car.js` | 차량 앱 없이 가짜 차가 도로를 따라 달려와 웹 흐름을 끝까지 본다 |
| 차량 앱 debug 빌드 | 시연 버튼(모의 주행·변경 요청·도착·탑승)과 SDK 값을 그대로 보여주는 "SDK 확인" 화면 |
| Pleos Virtual Console | 에뮬레이터 차량 신호(기어·문·경고등)를 바꾼다. 실행 방법은 README |
