# UniconLab Wheelchair Operator App

UniconLab 자율주행 휠체어를 휴대폰에서 **블루투스로** 지켜보고 조작하는 안드로이드 앱입니다.
휠체어의 NUC(ROS 1)에서 도는 브릿지와 줄 단위 JSON을 주고받으며, 주행 상태·경로상 위치·
라이다가 본 주변 물체를 보여 주고 E-STOP, 시동, 주행 시작/정지, 스택 기동 명령을 보냅니다.

> 이 앱의 출발점이 된 소스 코드는 박형준 님의
> [edge-mobility-monitor](https://github.com/Geppetto0608/edge-mobility-monitor)에서
> 제공받았습니다. 자세한 고지는 [`NOTICE.md`](NOTICE.md), 원본 라이선스는 [`LICENSE`](LICENSE)에 있습니다.

## 구성

```text
Android 앱 ──── Bluetooth SPP (RFCOMM, UUID 0x1101) ────  NUC
                한 줄 = JSON 하나                          ros1_bluetooth_bridge.py
  ← 텔레메트리 프레임 (약 2 Hz)                                 │
  ← route / ack 프레임                                         ├─ ROS 1 토픽 구독 (pose, 속도, 장애물, 안전 게이트 …)
  → {"command": "...", "confirm": true}                        └─ 명령을 모드 전환·주행·스택 기동으로 실행
```

앱에는 휠체어 쪽 로직이 없습니다. 무엇이 안전한지, 주행 가능한지는 **브릿지가 판단해서 보내고**,
앱은 그것을 그대로 표시합니다. 브릿지는 휠체어 저장소에 있습니다:
[`scripts/ros1_bluetooth_bridge.py` @ `42f209e`](https://github.com/gyeong-yeon2718/Uniconlab-autonomous-wheelchair/blob/42f209e/scripts/ros1_bluetooth_bridge.py)
— 주변 장애물 지도는 이 버전 이상이어야 그려집니다.

## 화면 흐름

1. **연결 화면** (`WifiSetupActivity`, 런처) — 페어링된 기기 목록에서 NUC를 고르거나
   [블루투스 1터치 연결]로 마지막에 붙었던 NUC에 바로 연결합니다. 이름은 원본의 Wi-Fi 설정
   화면에서 왔지만 지금은 블루투스 연결만 합니다.
2. **대시보드** (`MainActivity`) — 연결되면 넘어오는 화면. 아래 핵심 코드 대부분이 여기에 모입니다.

## 핵심 코드

모두 `app/src/main/java/com/example/jetsonbtmonitor/` 아래에 있습니다.

| 파일 | 역할 |
| :--- | :--- |
| [`BluetoothWheelchairClient.java`](app/src/main/java/com/example/jetsonbtmonitor/BluetoothWheelchairClient.java) | RFCOMM 소켓 연결, 줄 단위 송수신, 자동 재연결 |
| [`MainActivity.java`](app/src/main/java/com/example/jetsonbtmonitor/MainActivity.java) | 프레임 분기, 대시보드 렌더링, 명령 버튼, 링크 감시 |
| [`RouteMapView.java`](app/src/main/java/com/example/jetsonbtmonitor/RouteMapView.java) | 캠퍼스 위 경로·현재 위치·진행률 지도 |
| [`ObstacleFieldView.java`](app/src/main/java/com/example/jetsonbtmonitor/ObstacleFieldView.java) | 휠체어 중심의 주변 물체 지도 (라이다 프레임) |
| [`AppConfig.java`](app/src/main/java/com/example/jetsonbtmonitor/AppConfig.java) | 빌드 스위치 (`BLUETOOTH_ONLY`, `CAMERA_ENABLED`) |

### `BluetoothWheelchairClient` — 링크

- **SDP로 SPP 서비스를 먼저 찾습니다.** NUC는 핸즈프리(HFP/HSP)도 광고하기 때문에, 채널
  번호로 아무 데나 붙으면 핸즈프리에 연결되고 `AT+VGS=15`가 들어옵니다. 그래서
  `createRfcommSocketToServiceRecord(SPP UUID)`가 우선이고, 채널 1–5 직접 탐색은
  **사용자가 직접 누른 첫 연결에서만** 마지막 수단으로 씁니다.
- 연결 전에 `fetchUuidsWithSdp()`로 서비스 목록을 새로 받습니다. 브릿지가 꺼져 있을 때 캐시된
  목록에는 SPP가 없어서, 브릿지가 다시 떠도 UUID 연결이 계속 실패하기 때문입니다.
- **자동 재연결:** 끊기면 2 s → 4 s → 8 s → 이후 15 s 간격으로 계속 다시 붙습니다. 재시도는
  SDP 경로만 쓰므로 핸즈프리에 잘못 붙지 않습니다.
- `forceReconnect()` — 소켓은 살아 있는데 데이터가 안 오는 경우(브릿지가 죽어도 ACL 링크는 유지됨)를
  대시보드가 감지하면 이걸로 소켓을 닫아 재연결 경로로 보냅니다.
- 앱 전체에서 인스턴스 하나(`getSharedInstance`)를 공유해서, 연결 화면에서 연 링크를 대시보드가 이어받습니다.

### `MainActivity` — 대시보드

**수신 프레임 분기** (`setupBluetoothClient` → `onLineReceived`)

| 프레임 | 처리 |
| :--- | :--- |
| `{"type":"route", "points":[…], "stride", "count", "count_full"}` | 경로를 두 지도에 넣고 ETA 기준을 초기화 |
| `{"type":"ack", "ok", "detail"}` | 명령 결과. `detail`을 그대로 표시 |
| `protocol_version`이 있는 객체 | 텔레메트리 → `renderBridgeTelemetry` |
| 그 밖의 줄 | `noteForeignData` — 잘못된 서비스(대개 핸즈프리)로 판단, 조작을 막고 재연결 |

**텔레메트리 렌더링** (`renderBridgeTelemetry`에서 나뉨)

- `renderDriveState` — `estop_engaged`, `manual_idle`, `follower_state`, `motion_blocked`,
  `ready_to_drive`로 지금 누를 수 있는 버튼을 정합니다. 정지 사유는 `holdReasonKo`가 한국어로 바꿉니다.
- `renderSubsystems` — 휠 링크, 측위(`loc_fitness`, `loc_inlier_ratio`), 차체 기울기
  (`pose_roll_deg`, `pose_pitch_deg`), 장애물, 속도 게이트를 초록/노랑/빨강 행으로 표시합니다.
- `renderNavSection` / `renderTrip` — `pose_x`, `pose_y`, `pose_yaw_deg`, `wp_index`/`wp_total`로
  경로 지도와 남은 거리·ETA를 갱신합니다.
- `renderObstacleField` — `objects`, `objects_frame`, `objects_hidden` 등을 주변 물체 지도로 넘깁니다.
- `renderJobSection` — [로컬 켜기]로 띄운 기동 스크립트의 진행 상태(`job_state`, `job_tail`, `stack_nodes_up`/`total`).

**보내는 명령** — 모두 `{"command": "<이름>"}` 한 줄이고, 움직임을 일으키는 명령에는 `"confirm": true`가 붙습니다.

| 명령 | 버튼 | 확인 창 |
| :--- | :--- | :--- |
| `estop` | E-STOP | 없음 (비상 정지 앞에 확인 창을 두지 않음) |
| `arm_and_drive` | 시동 + 주행 / E-STOP 해제 | 있음 |
| `drive_start` / `drive_stop` | 주행 시작 / 정지 | 시작만 있음 |
| `stack_start` / `stack_stop` | 로컬 켜기 / 스택 내리기 | 있음 |
| `job_cancel` | 기동 중단 | 있음 |
| `ping` | 연결 확인 / 새로고침 | — |
| `route` | (자동) 경로가 없으면 4초마다 요청 | — |

**링크 감시** — 프레임이 3초 동안 안 오면(`TELEMETRY_STALE_MS`) 속도를 지우고, 조작 버튼을 막고,
`forceReconnect()`를 부릅니다. 화면에 남은 값이 지금 값처럼 보이지 않게 하려는 것입니다.
대시보드가 떠 있는 동안 화면이 꺼지지 않게(`FLAG_KEEP_SCREEN_ON`) 해서 안드로이드가 수신
스레드를 얼리지 않게 합니다.

### `RouteMapView` — 경로 지도

- 경로와 `/fast_lio_icp/pose`가 같은 `map` 프레임이라 변환 없이 같은 축에 그립니다
  (ROS x-앞/y-왼쪽 → 화면 x-오른쪽/y-아래로 y만 뒤집음).
- 배경(`res/drawable-nodpi/campus_backdrop.png`)은 측위가 매칭하는 바로 그 점군에서 렌더링한
  이미지라서 `BACKDROP_ORIGIN_X/Y`, `BACKDROP_RES_M` 세 값만으로 위치가 맞습니다.
- 건물 이름(`LANDMARKS`)과 북쪽(`MAP_YAW_OF_NORTH_DEG`, ±15° 정도의 실측값)이 들어 있습니다.
  **지도를 다시 만들면 이 값들도 다시 재야 합니다.**
- pose가 오래되면 휠체어 표시를 회색으로 바꿉니다. 전체 화면에서는 확대·이동·회전이 됩니다.

### `ObstacleFieldView` — 주변 물체 지도

- 휠체어를 가운데 둔 위에서 본 그림. 좌표는 브릿지가 보내는 **라이다 프레임**(x 앞, y 왼쪽)
  그대로이고 pose로 변환하지 않습니다. 그래서 측위가 틀어져도 이 그림은 영향을 받지 않습니다.
- 물체 색은 클래스(`person` / `vehicle` / `obstacle`)별이고, 경로 안에 있는지는 앱이 계산하지 않고
  로봇이 보낸 `band_relation`을 씁니다.
- `trail`은 최근 3초 동안 실제로 관측된 위치라서, 서 있는 물체는 꼬리가 없습니다.
- 범위는 4/6/10/15/20/30 m 단계로만 바뀝니다. 매 프레임 눈금이 움직이지 않게 하려는 것입니다.
- 휠체어 외곽(앞뒤 0.5 m, 좌우 0.3 m)은 크기 비교용일 뿐, 안전 판단에 쓰는 값이 아닙니다.

## 이 빌드에서 쓰지 않는 코드

`AppConfig.BLUETOOTH_ONLY = true`라서 아래는 꺼져 있거나 화면에 나오지 않습니다. 원본의
Jetson/Wi-Fi 구성에서 쓰던 것들입니다.

- `WheelchairApiClient`, `WheelchairBackgroundService` — HTTP 폴링과 백그라운드 알림
- `CameraActivity` — MJPEG 카메라 (`CAMERA_ENABLED = false`)
- `LoginActivity`, `RegisterActivity`, `UserSession`, 대시보드의 프리셋 — 사용자/프리셋 기능
- `WheelchairTelemetryParser` 계열 — Wi-Fi 텔레메트리 파서 (블루투스는 `renderBridgeTelemetry`가 직접 읽음)
- `jetson/` — Jetson용 ROS 2 브릿지와 스크립트

## 빌드와 설치

필요한 것: Android SDK, JDK 21 (Gradle 데몬 툴체인 버전. Android Studio에 들어 있는 `jbr`로 충분).

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:testDebugUnitTest
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows에서 `JAVA_HOME`이 없으면 `C:\Program Files\Android\Android Studio\jbr`로 잡아 주세요.

## 휠체어 쪽에서 확인할 것

앱이 연결되지 않으면 대개 브릿지가 안 떠 있는 경우입니다. NUC에서:

```bash
bluetoothctl show | grep -i serial
```

`Serial Port (00001101-…)`가 안 보이면 브릿지가 내려가 있는 것입니다.

```bash
systemctl --user restart wheelchair-bt-bridge
```

## 라이선스

이 앱은 박형준 님이 제공한 [edge-mobility-monitor](https://github.com/Geppetto0608/edge-mobility-monitor)
소스를 바탕으로 한 파생 저작물이며, 원저작자의 허락을 받아 공개합니다. 원본은 오픈소스가 아니며
([`LICENSE`](LICENSE)), 이 저장소를 볼 수 있다고 해서 사용·복제·수정·재배포 권리가 생기지는 않습니다.
