package com.example.jetsonbtmonitor;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.card.MaterialCardView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final long TELEMETRY_POLL_INTERVAL_MS = 1_000L;
    private static final String PRESET_BASE = "base";
    private static final String PRESET_FAST = "fast";
    private static final String PRESET_SLOW = "slow";
    private static final String PRESET_PREFS_NAME = "wheelchair_user_presets";
    private static final String[] PRESET_IDS = {PRESET_BASE, PRESET_FAST, PRESET_SLOW};
    private static final String[] PRESET_MODE_VALUES = {"manual", "assist", "autonomous"};
    private static final String[] PRESET_MODE_LABELS = {"수동", "어시스트", "자율주행"};
    private static final String[] PRESET_STEP_LABELS = {"1 단계", "2 단계", "3 단계", "4 단계", "5 단계"};

    private final DecimalFormat speedFormat = new DecimalFormat("0.0");
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WheelchairApiClient apiClient;
    private ScrollView dashboardScrollView;
    private Button refreshButton;
    private Button connectButton;
    private TextView connectionStateText;
    private TextView bluetoothBadgeText;
    private TextView speedMpsText;
    private TextView speedText;
    private TextView modeText;
    private TextView stepLevelText;
    private TextView systemStateText;
    private TextView rawJsonText;
    private MaterialButtonToggleGroup modeToggleGroup;
    private MaterialCardView presetBaseCard;
    private MaterialCardView presetFastCard;
    private MaterialCardView presetSlowCard;
    private TextView presetBaseNameText;
    private TextView presetFastNameText;
    private TextView presetSlowNameText;
    private TextView presetBaseSummaryText;
    private TextView presetFastSummaryText;
    private TextView presetSlowSummaryText;
    private MaterialButton presetBaseSettingsButton;
    private MaterialButton presetFastSettingsButton;
    private MaterialButton presetSlowSettingsButton;
    private MaterialButton openCameraButton;
    private MaterialButton settingsButton;
    private MaterialButton stepUpButton;
    private MaterialButton stepDownButton;
    private MaterialButton stopButton;
    private MaterialButton estopReleaseButton;
    private MaterialButton driveStartButton;
    private MaterialButton driveStopButton;
    private TextView readinessText;
    private TextView subsystemText;
    private TextView followerStatusText;
    private TextView jobStatusText;
    private MaterialButton stackStartButton;
    private MaterialButton jobCancelButton;
    private RouteMapView routeMapView;
    private TextView navStatusText;
    private TextView estopHintText;
    private boolean sawProtocolFrame;
    private int foreignLineCount;

    private int stepLevel = 3;
    /** Latched from telemetry so the arm/release button knows which it is. */
    private boolean estopEngaged;

    /**
     * Link watchdog. The bridge sends at 2 Hz, so six missed frames is a dead
     * link -- and a dead link does not announce itself: when the bridge process
     * dies or the phone walks out of range, the ACL connection stays up and the
     * socket read simply never returns. Without this the dashboard kept saying
     * 연결됨 over frozen numbers, which is worse than saying nothing.
     */
    /**
     * The route frame is sent once per connection, ahead of any telemetry, and
     * the device picker owns the shared Bluetooth callback until this activity
     * swaps itself in -- so the one copy that mattered was routinely dropped and
     * the map stayed blank for the session. Ask for it until we have one.
     */
    private static final long ROUTE_REQUEST_INTERVAL_MS = 4_000L;
    private long lastRouteRequestAt;

    private static final long TELEMETRY_STALE_MS = 3_000L;
    private static final long WATCHDOG_TICK_MS = 1_000L;
    private long lastFrameAt;
    private boolean linkStale;

    private final Runnable linkWatchdog = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            if (AppConfig.BLUETOOTH_ONLY && lastFrameAt > 0 && !linkStale
                    && System.currentTimeMillis() - lastFrameAt > TELEMETRY_STALE_MS) {
                onTelemetryStale();
            }
            mainHandler.postDelayed(this, WATCHDOG_TICK_MS);
        }
    };
    private JSONObject presetsById = new JSONObject();
    private String defaultPresetId = "";
    private String activePresetId = "";
    private String lastDisplayedMode = "";
    private boolean defaultPresetApplied;
    private boolean updatingModeSelection;
    private boolean destroyed;

    private final Runnable telemetryPollRunnable = new Runnable() {
        @Override
        public void run() {
            pollTelemetry();
            if (!destroyed) {
                mainHandler.postDelayed(this, TELEMETRY_POLL_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The login gate belongs to the upstream Wi-Fi build, where the Jetson
        // bridge kept a user store. The NUC has none, so requiring a login here
        // just bounced the user straight back to the connect screen after a
        // successful Bluetooth connection.
        if (!AppConfig.BLUETOOTH_ONLY && !UserSession.isLoggedIn(this)) {
            startActivity(new Intent(this, WifiSetupActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);
        apiClient = new WheelchairApiClient(AppConfig.JETSON_API_BASE_URL);
        bindViews();
        setupActions();
        setupBackNavigation();
        setConnectedUi(false);
        setupDefaultPresets();
        loadLocalPresets();
        renderPresetCards();
        resetScrollPosition();
        setupBluetoothClient();
        mainHandler.postDelayed(linkWatchdog, WATCHDOG_TICK_MS);
        requestNotificationPermissionIfNeeded();

        // Bluetooth is the only transport to the NUC. The Wi-Fi HTTP paths below
        // point at an address that does not exist in this deployment, so leaving
        // them running meant a failed request every 5 s from a foreground service
        // plus a poller that could overwrite live Bluetooth telemetry with errors.
        // Keep them behind the flag so the Jetson/Wi-Fi build still works.
        if (!AppConfig.BLUETOOTH_ONLY) {
            loadPresets();
            startTelemetryPolling();
            WheelchairBackgroundService.start(this);
        }
    }

    private BluetoothWheelchairClient bluetoothClient;

    private void setupBluetoothClient() {
        bluetoothClient = BluetoothWheelchairClient.getSharedInstance(new BluetoothWheelchairClient.Callback() {
            @Override
            public void onConnected(android.bluetooth.BluetoothDevice device) {
                runOnUiThread(() -> {
                    setConnectedUi(true);
                    String devName = "NUC Bluetooth";
                    try {
                        if (device != null && device.getName() != null) devName = device.getName();
                    } catch (SecurityException ignored) {}
                    connectionStateText.setText("BT 연결됨 (" + devName + ")");
                    // Arm the watchdog from the connect, not from the first
                    // frame: a socket that opens and then delivers nothing is
                    // exactly the case that needs catching (wrong service, or a
                    // bridge that died between SDP and the first broadcast).
                    lastFrameAt = System.currentTimeMillis();
                    linkStale = false;
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    setConnectedUi(false);
                    connectionStateText.setText("BT 연결 끊김");
                    // Nothing on screen is current any more, and a dashboard that
                    // keeps offering E-STOP over a dead link is worse than one
                    // that admits it cannot reach the chair.
                    disableRobotControls();
                });
            }

            @Override
            public void onReconnecting(int attempt, long delayMs) {
                runOnUiThread(() -> {
                    setConnectionState(getString(R.string.connection_reconnecting),
                            R.drawable.status_dot_connecting,
                            R.color.status_connecting_text);
                    connectionStateText.setText(String.format(Locale.US,
                            "재연결 시도 %d회 — %.0f초 후", attempt, delayMs / 1000.0));
                });
            }

            @Override
            public void onLineReceived(String line) {
                runOnUiThread(() -> {
                    setConnectedUi(true);
                    try {
                        JSONObject frame = new JSONObject(line);
                        String type = frame.optString("type");
                        if ("route".equals(type)) {
                            routeMapView.setRoute(frame.optJSONArray("points"),
                                    frame.optInt("stride", 1),
                                    frame.optInt("count_full", frame.optInt("count")));
                            int full = frame.optInt("count_full", frame.optInt("count"));
                            int sent = frame.optInt("count");
                            showStatus(full == sent
                                    ? ("경로 수신: " + full + "개 지점")
                                    : ("경로 수신: " + full + "개 중 " + sent + "개 표시 ("
                                       + frame.optString("source") + ")"));
                            return;
                        }
                        if ("ack".equals(type)) {
                            noteFrameArrived();
                            // The bridge's detail text is written to be shown as-is.
                            String detail = frame.optString("detail");
                            showStatus((frame.optBoolean("ok") ? "✓ " : "거부: ") + detail);
                            rawJsonText.setText(detail);
                            return;
                        }
                        if (!frame.has("protocol_version")) {
                            noteForeignData(line);
                            return;
                        }
                        sawProtocolFrame = true;
                        foreignLineCount = 0;
                        noteFrameArrived();
                        requestRouteIfMissing();
                        renderBridgeTelemetry(frame);
                        rawJsonText.setText(line);
                    } catch (Exception e) {
                        noteForeignData(line);
                    }
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus("BT: " + message));
            }
        });

        if (bluetoothClient.isConnected()) {
            setConnectedUi(true);
            connectionStateText.setText("BT 연결됨");
        }
    }

    private void renderTelemetryFromBt(WheelchairTelemetryUpdate update, String rawLine) {
        if (update != null) {
            if (update.getSpeedKph() != null) {
                double speedMps = update.getSpeedKph() / 3.6;
                speedMpsText.setText(speedFormat.format(speedMps));
                speedText.setText(speedFormat.format(update.getSpeedKph()) + " km/h");
            }
            if (update.getMode() != null) {
                updateDisplayedMode(update.getMode());
                syncModeSelection(displayModeForTelemetry(update.getMode()));
            }
            systemStateText.setText(R.string.system_state_running);
            rawJsonText.setText("NUC Bluetooth 수신:\n" + rawLine);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(telemetryPollRunnable);
        mainHandler.removeCallbacks(linkWatchdog);
        if (apiClient != null) {
            apiClient.shutdown();
        }
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resetScrollPosition();
    }

    private void bindViews() {
        dashboardScrollView = findViewById(R.id.dashboardScrollView);
        refreshButton = findViewById(R.id.refreshButton);
        connectButton = findViewById(R.id.connectButton);
        connectionStateText = findViewById(R.id.connectionStateText);
        bluetoothBadgeText = findViewById(R.id.bluetoothBadgeText);
        if (AppConfig.BLUETOOTH_ONLY) {
            bluetoothBadgeText.setText("BT");
        }
        speedMpsText = findViewById(R.id.speedMpsText);
        speedText = findViewById(R.id.speedText);
        modeText = findViewById(R.id.modeText);
        systemStateText = findViewById(R.id.systemStateText);
        rawJsonText = findViewById(R.id.rawJsonText);
        presetBaseCard = findViewById(R.id.presetBaseCard);
        presetFastCard = findViewById(R.id.presetFastCard);
        presetSlowCard = findViewById(R.id.presetSlowCard);
        presetBaseNameText = findViewById(R.id.presetBaseNameText);
        presetFastNameText = findViewById(R.id.presetFastNameText);
        presetSlowNameText = findViewById(R.id.presetSlowNameText);
        presetBaseSummaryText = findViewById(R.id.presetBaseSummaryText);
        presetFastSummaryText = findViewById(R.id.presetFastSummaryText);
        presetSlowSummaryText = findViewById(R.id.presetSlowSummaryText);
        presetBaseSettingsButton = findViewById(R.id.presetBaseSettingsButton);
        presetFastSettingsButton = findViewById(R.id.presetFastSettingsButton);
        presetSlowSettingsButton = findViewById(R.id.presetSlowSettingsButton);
        openCameraButton = findViewById(R.id.openCameraButton);
        settingsButton = findViewById(R.id.settingsButton);
        stopButton = findViewById(R.id.stopButton);
        estopReleaseButton = findViewById(R.id.estopReleaseButton);
        estopHintText = findViewById(R.id.estopHintText);
        driveStartButton = findViewById(R.id.driveStartButton);
        driveStopButton = findViewById(R.id.driveStopButton);
        readinessText = findViewById(R.id.readinessText);
        subsystemText = findViewById(R.id.subsystemText);
        followerStatusText = findViewById(R.id.followerStatusText);
        jobStatusText = findViewById(R.id.jobStatusText);
        stackStartButton = findViewById(R.id.stackStartButton);
        jobCancelButton = findViewById(R.id.jobCancelButton);
        routeMapView = findViewById(R.id.routeMapView);
        // Matches the speed readout's height plus its margin, so the fitted
        // route never runs underneath it.
        routeMapView.setBottomInset(
                getResources().getDisplayMetrics().density * 44f);
        navStatusText = findViewById(R.id.navStatusText);
    }

    private void setupActions() {
        // These two used to call the Jetson HTTP API, which does not exist in this
        // deployment -- pressing them reported "Jetson Wi-Fi API 에 접근할 수 없습니다"
        // on a link that was working perfectly. On Bluetooth they ping the bridge
        // instead, which is what the operator is actually asking about.
        if (AppConfig.BLUETOOTH_ONLY) {
            connectButton.setOnClickListener(view -> pingBridge());
            refreshButton.setOnClickListener(view -> pingBridge());
        } else {
            refreshButton.setOnClickListener(view -> pollTelemetry());
            connectButton.setOnClickListener(view -> checkApiConnection());
        }
        settingsButton.setOnClickListener(view -> startActivity(new Intent(this, SettingsActivity.class)));
        if (AppConfig.CAMERA_ENABLED) {
            openCameraButton.setOnClickListener(view -> openCameraStream());
        } else {
            // The NUC runs no MJPEG server. Hide the whole card, not just the
            // button: an empty "Camera Stream" panel still implies there is one.
            View cameraCard = findViewById(R.id.cameraCard);
            if (cameraCard != null) {
                cameraCard.setVisibility(View.GONE);
            }
            openCameraButton.setVisibility(View.GONE);
        }
        // Presets encode mode+step, neither of which exists in this stack.
        hidePresetSection();

        // E-STOP is one press on purpose: a confirm dialog in front of an
        // emergency control is a hazard, not a safeguard.
        stopButton.setOnClickListener(view -> sendBridgeCommand("estop", false,
                "E-STOP을 보냈습니다."));
        // One command (estop_release -> mode_cmd=65) covering two situations:
        // undoing an E-STOP, and arming a base that is simply resting in manual
        // after bring-up. The rider has to judge a different thing in each case,
        // so the dialog asks the question that belongs to the state it is in.
        estopReleaseButton.setOnClickListener(view -> confirmThen(
                estopEngaged ? "E-STOP 해제" : "자동 모드 전환",
                estopEngaged
                        ? "휠체어 주변이 안전한지 확인하셨습니까?\n\n해제해도 주행은 재개되지 않습니다."
                        : "베이스를 자동 모드로 전환합니다.\n\n조종간 조작이 무시되며, "
                          + "전환만으로는 움직이지 않습니다. 주행은 [주행 시작]을 따로 눌러야 합니다.",
                () -> sendBridgeCommand("estop_release", true,
                        estopEngaged ? "E-STOP 해제를 요청했습니다." : "자동 모드 전환을 요청했습니다.")));
        driveStartButton.setOnClickListener(view -> confirmThen(
                "주행 시작",
                "휠체어가 즉시 움직이기 시작합니다. 계속할까요?",
                () -> sendBridgeCommand("drive_start", true, "주행 시작을 요청했습니다.")));
        driveStopButton.setOnClickListener(view -> sendBridgeCommand("drive_stop", false,
                "주행 정지를 요청했습니다."));
        stackStartButton.setOnClickListener(view -> confirmThen(
                "로컬 켜기",
                "라이다·IMU·측위 노드를 기동합니다.\n\n수 분 걸리며, 휠체어는 움직이지 않습니다.",
                () -> sendBridgeCommand("stack_start", true, "시스템 기동을 시작했습니다.")));
        jobCancelButton.setOnClickListener(view -> confirmThen(
                "기동 중단",
                "실행 중인 기동 스크립트를 중단합니다. 계속할까요?",
                () -> sendBridgeCommand("job_cancel", false, "중단 신호를 보냈습니다.")));
    }


    // ---------------------------------------------------------- bridge control

    /** Presets carry mode+step, neither of which exists in the live stack. */
    private void hidePresetSection() {
        View heading = findViewById(R.id.presetSectionTitle);
        for (View v : new View[]{heading, presetBaseCard, presetFastCard, presetSlowCard}) {
            if (v != null) {
                v.setVisibility(View.GONE);
            }
        }
    }

    private void confirmThen(String title, String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton("취소", null)
                .setPositiveButton("확인", (d, w) -> action.run())
                .show();
    }

    /** Same wire format, but no status-line noise: this one is housekeeping. */
    private void sendBridgeCommandQuietly(String command) {
        if (bluetoothClient == null || !bluetoothClient.isConnected()) {
            return;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("command", command);
            bluetoothClient.sendJsonCommand(payload);
        } catch (JSONException ignored) {
        }
    }

    private void sendBridgeCommand(String command, boolean confirm, String pendingMessage) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("command", command);
            if (confirm) {
                payload.put("confirm", true);
            }
        } catch (JSONException exception) {
            showStatus("명령 생성에 실패했습니다.");
            return;
        }
        sendCommand(payload, pendingMessage);
    }

    /** Renders a protocol-v3 telemetry frame. The bridge is the source of truth. */
    private void renderBridgeTelemetry(JSONObject f) {
        if (!f.isNull("speed_mps")) {
            double mps = f.optDouble("speed_mps");
            speedMpsText.setText(speedFormat.format(mps));
            speedText.setText(speedFormat.format(mps * 3.6) + " km/h");
        } else {
            // No fresh odometry. Leaving the last number on screen would show a
            // speed the chair had some unknown time ago as if it were current.
            speedMpsText.setText(R.string.empty_value);
            speedText.setText(R.string.empty_speed_kph);
        }
        boolean estop = f.optBoolean("estop_engaged", false);
        boolean pending = f.optBoolean("estop_pending", false);
        // Manual after bring-up is the normal resting state, not an emergency.
        boolean manualIdle = f.optBoolean("manual_idle", false);
        estopEngaged = estop;
        String mode = f.isNull("drive_mode") ? null : f.optString("drive_mode");
        modeText.setText(estop ? "정지 (수동)"
                : "auto".equals(mode) ? "자율 (auto)"
                : "manual".equals(mode) ? "수동 (manual)"
                : getString(R.string.empty_value));

        systemStateText.setText(estop ? "E-STOP 작동 중"
                : pending ? "E-STOP 반영 대기…"
                : manualIdle ? "수동 모드 (대기)" : "정상");
        systemStateText.setTextColor(ContextCompat.getColor(this,
                estop ? R.color.danger_red : R.color.text_primary));

        boolean ready = f.optBoolean("ready_to_drive", false);
        boolean blocked = f.optBoolean("motion_blocked", false);
        String readiness;
        int bg, fg;
        if (estop) {
            readiness = "E-STOP — 해제해야 주행할 수 있습니다";
            bg = R.color.status_reconnecting_bg;
            fg = R.color.status_disconnected_text;
        } else if (manualIdle) {
            // [주행 시작] does NOT arm the base -- the bridge refuses while the
            // drive mode reads manual. Point at the button that actually arms it.
            readiness = "수동 모드 — [자동 모드 전환]으로 시동을 건 뒤 주행할 수 있습니다";
            bg = R.color.status_waiting_bg;
            fg = R.color.status_waiting_text;
        } else if (blocked) {
            readiness = "안전 게이트가 주행을 보류 중입니다";
            bg = R.color.status_connecting_bg;
            fg = R.color.status_connecting_text;
        } else if (ready) {
            readiness = "주행 준비 완료";
            bg = R.color.status_connected_bg;
            fg = R.color.status_connected_text;
        } else {
            readiness = "주행 조건 미충족";
            bg = R.color.status_waiting_bg;
            fg = R.color.status_waiting_text;
        }
        readinessText.setText(readiness);
        readinessText.setBackgroundColor(ContextCompat.getColor(this, bg));
        readinessText.setTextColor(ContextCompat.getColor(this, fg));

        subsystemText.setText(String.format(Locale.US,
                "휠 링크 %s   ·   측위 %s   ·   경사 %s\n장애물 %s\n결함 %s",
                f.optBoolean("wheel_link_ok", false) ? "정상" : "끊김",
                subsystemValue(f, "localization_status"),
                subsystemValue(f, "tip_guard_status"),
                subsystemValue(f, "objects_summary"),
                subsystemValue(f, "robot_fault")));
        // A missing reading is a different fact from a clean one, and the line
        // greys as a whole so the difference is visible without reading it.
        subsystemText.setTextColor(ContextCompat.getColor(this,
                hasUnavailableSubsystem(f) ? R.color.text_tertiary : R.color.text_secondary));

        String followerStatus = f.isNull("follower_status")
                ? getString(R.string.empty_value) : f.optString("follower_status");
        // The follower appends POLICIES_OFF when the discretionary guards are
        // suppressed (trial bring-up). That must not be a detail buried in a
        // status string -- it changes what the chair will drive through.
        boolean policiesOff = followerStatus.contains("POLICIES_OFF");
        if (policiesOff) {
            followerStatus = "⚠ 안전 정책 꺼짐 (POLICIES_OFF)\n" + followerStatus;
        }
        followerStatusText.setText(followerStatus);
        followerStatusText.setTextColor(ContextCompat.getColor(this,
                policiesOff ? R.color.danger_red : R.color.text_primary));

        // Grey out what the robot cannot honour rather than letting a press fail.
        boolean canDrive = f.optBoolean("follower_start_available", false);
        // The bridge refuses drive_start on any manual reading, not just an
        // E-STOP, so an enabled button there is a press that cannot succeed.
        driveStartButton.setEnabled(canDrive && !estop && !manualIdle);
        driveStopButton.setEnabled(canDrive);
        // Arming and releasing are the same lever. Offering it only after an
        // E-STOP left a freshly brought-up chair with no way to arm at all:
        // [주행 시작] refused and pointed at a button that was greyed out.
        estopReleaseButton.setEnabled(estop || manualIdle);
        estopReleaseButton.setText(estop ? R.string.estop_release : R.string.mode_arm);
        // The hint under the button explained the release even when the button
        // was offering to arm, which is the opposite half of the story.
        estopHintText.setText(estop ? R.string.estop_hint : R.string.mode_arm_hint);

        renderNavSection(f);
        renderJobSection(f);
    }

    /**
     * Something is talking to us, but it is not the bridge.
     *
     * The NUC also advertises Hands-Free, and a socket opened onto that service
     * connects perfectly happily and then sends AT commands. Reporting "연결됨"
     * over that is the worst outcome -- the operator would trust a dashboard that
     * is wired to nothing. Say plainly what happened instead.
     */
    /** Round-trips a ping so "연결 확인" tests the link that is actually in use. */
    private void pingBridge() {
        if (bluetoothClient == null || !bluetoothClient.isConnected()) {
            setConnectedUi(false);
            connectionStateText.setText("BT 연결 끊김");
            showStatus("블루투스가 연결되어 있지 않습니다.");
            return;
        }
        showStatus("브릿지 응답 확인 중…");
        sendBridgeCommand("ping", false, "ping");
    }

    private void noteForeignData(String line) {
        foreignLineCount++;
        rawJsonText.setText("BT RX (알 수 없는 형식): " + line);
        if (sawProtocolFrame) {
            return;
        }
        // One line is already proof. Waiting for a second meant Hands-Free --
        // which sends a single AT+VGS on connect and then goes quiet -- sat
        // there under a 연결됨 header indefinitely.
        boolean atCommand = line != null && line.startsWith("AT+");
        readinessText.setText(atCommand
                ? "잘못된 서비스에 연결됨 — NUC의 핸즈프리(HFP)입니다"
                : "잘못된 서비스에 연결됨 — 브릿지 응답이 아닙니다");
        readinessText.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_reconnecting_bg));
        readinessText.setTextColor(
                ContextCompat.getColor(this, R.color.status_disconnected_text));
        subsystemText.setText("NUC에서 브릿지가 실행 중인지 확인하세요:\n"
                + "  ./scripts/nuc_bridge_restart.sh --allow-commands --allow-scripts\n"
                + "브릿지가 꺼져 있으면 SPP 레코드가 사라지고, 앱이 다른 서비스에 붙습니다.");
        subsystemText.setTextColor(
                ContextCompat.getColor(this, R.color.status_disconnected_text));
        // Nothing here is real, so nothing here may be commanded.
        disableRobotControls();
        setConnectionState(getString(R.string.connection_reconnecting),
                R.drawable.status_dot_connecting,
                R.color.status_connecting_text);
        connectionStateText.setText("잘못된 서비스 — 브릿지 재탐색 중");
        // Drop it and retry. The retry path is SDP-only, so it cannot land back
        // on Hands-Free; it will simply keep failing until the bridge is up.
        if (bluetoothClient != null) {
            bluetoothClient.forceReconnect();
        }
    }

    /** Cheap to ask, and it stops as soon as a route is on screen. */
    private void requestRouteIfMissing() {
        if (routeMapView == null || routeMapView.hasRoute()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRouteRequestAt < ROUTE_REQUEST_INTERVAL_MS) {
            return;
        }
        lastRouteRequestAt = now;
        sendBridgeCommandQuietly("route");
    }

    private void noteFrameArrived() {
        lastFrameAt = System.currentTimeMillis();
        linkStale = false;
    }

    /**
     * Frames stopped arriving. Say so, stop offering commands that cannot be
     * delivered, and close the socket so the reconnect path can take over --
     * the read is blocked and will not notice on its own.
     */
    private void onTelemetryStale() {
        linkStale = true;
        setConnectionState(getString(R.string.connection_reconnecting),
                R.drawable.status_dot_connecting,
                R.color.status_connecting_text);
        connectionStateText.setText("텔레메트리 수신 끊김 — 재연결 시도 중");
        speedMpsText.setText(R.string.empty_value);
        speedText.setText(R.string.empty_speed_kph);
        readinessText.setText("링크 끊김 — 표시된 값은 마지막 수신 값입니다");
        readinessText.setBackgroundColor(
                ContextCompat.getColor(this, R.color.status_reconnecting_bg));
        readinessText.setTextColor(
                ContextCompat.getColor(this, R.color.status_disconnected_text));
        disableRobotControls();
        if (bluetoothClient != null) {
            bluetoothClient.forceReconnect();
        }
    }

    private static final String[] SUBSYSTEM_KEYS = {
            "localization_status", "tip_guard_status", "objects_summary", "robot_fault"};

    /**
     * "없음" used to cover both "the node reported nothing wrong" and "no node is
     * publishing this at all". For 결함 those read identically and mean opposite
     * things: /robot_fault has no publisher unless fault_check.py is launched, so
     * a silent fault channel was showing up as a clean bill of health.
     */
    private String subsystemValue(JSONObject f, String key) {
        if (f.isNull(key)) {
            return "데이터 없음";
        }
        String value = f.optString(key);
        // fault_check.py says "none" when every bit is clear.
        return "none".equals(value) ? "정상" : value;
    }

    private boolean hasUnavailableSubsystem(JSONObject f) {
        for (String key : SUBSYSTEM_KEYS) {
            if (f.isNull(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * No live link, no commands. The big red E-STOP is deliberately NOT among
     * these: greying out an emergency control teaches the operator to doubt it,
     * and a press that reports "not connected" is the more useful answer.
     */
    private void disableRobotControls() {
        driveStartButton.setEnabled(false);
        driveStopButton.setEnabled(false);
        estopReleaseButton.setEnabled(false);
        stackStartButton.setEnabled(false);
        jobCancelButton.setEnabled(false);
    }

    /** Route map plus the localization numbers that decide whether it is trustworthy. */
    private void renderNavSection(JSONObject f) {
        Float px = f.isNull("pose_x") ? null : (float) f.optDouble("pose_x");
        Float py = f.isNull("pose_y") ? null : (float) f.optDouble("pose_y");
        Float yaw = f.isNull("pose_yaw_deg") ? null : (float) f.optDouble("pose_yaw_deg");
        // A pose older than a second is not where the chair is now.
        boolean stale = f.isNull("pose_age_s") || f.optDouble("pose_age_s", 99) > 1.0;
        routeMapView.setPose(px, py, yaw, stale);

        // wp_index counts the follower's route; the drawn line is whatever route
        // the bridge resolved. When the two differ in length they are not the
        // same file, and dividing one index into the other puts the progress
        // marker on a line the chair is not driving. Draw the route, drop the
        // marker, and say so -- a wrong marker is worse than no marker.
        int countFull = routeMapView.getCountFull();
        int wpTotal = f.isNull("wp_total") ? 0 : f.optInt("wp_total");
        boolean routeMismatch = countFull > 0 && wpTotal > 0 && countFull != wpTotal;
        routeMapView.setProgress(
                (routeMismatch || f.isNull("wp_index")) ? -1 : f.optInt("wp_index"));

        String loc = f.isNull("localization_status") ? "없음" : f.optString("localization_status");
        StringBuilder line = new StringBuilder();
        line.append("측위 ").append(loc);
        if (!f.isNull("loc_fitness")) {
            // fitness and inlier ratio are the actual quality numbers; TRACKING
            // alone hides a fix that is drifting but has not been declared lost.
            line.append(String.format(Locale.US, "   ·   fitness %.3f", f.optDouble("loc_fitness")));
        }
        if (!f.isNull("loc_inlier_ratio")) {
            line.append(String.format(Locale.US, "   ·   inlier %.0f%%",
                    f.optDouble("loc_inlier_ratio") * 100.0));
        }
        if (!f.isNull("loc_reason") && !f.optString("loc_reason").isEmpty()) {
            line.append("\n사유 ").append(f.optString("loc_reason"));
        }
        if (!f.isNull("wp_index") && !f.isNull("wp_total")) {
            int done = f.optInt("wp_index");
            int total = f.optInt("wp_total");
            // Percent is what an operator can judge at a glance; 921/1917 needs
            // mental arithmetic before it means anything.
            line.append(total > 0
                    ? String.format(Locale.US, "%n경로 진행 %.0f%%  (%d/%d)",
                            100.0 * done / total, done, total)
                    : String.format(Locale.US, "%n경로 %d/%d", done, total));
            if (!f.isNull("follower_state")) {
                line.append("  ·  ").append(f.optString("follower_state"));
            }
        }
        if (routeMismatch) {
            line.append(String.format(Locale.US,
                    "\n⚠ 그려진 경로(%d점)와 팔로워 경로(%d점)가 다릅니다 — 진행 표시 숨김",
                    countFull, wpTotal));
        }
        if (px == null) {
            line.append("\n위치 수신 없음 — 측위가 아직 붙지 않았습니다");
        } else if (stale) {
            line.append("\n위치 갱신 끊김 (표시는 마지막 값)");
        }
        navStatusText.setText(line.toString());
        navStatusText.setTextColor(ContextCompat.getColor(this,
                (px == null || stale || routeMismatch)
                        ? R.color.status_disconnected_text : R.color.text_secondary));
    }

    /** Bring-up script progress. The bridge relays the script's own last log line. */
    private void renderJobSection(JSONObject f) {
        boolean scripts = f.optBoolean("scripts_enabled", false);
        int visibility = scripts ? View.VISIBLE : View.GONE;
        jobStatusText.setVisibility(visibility);
        stackStartButton.setVisibility(visibility);
        jobCancelButton.setVisibility(visibility);
        if (!scripts) {
            return;
        }

        String state = f.optString("job_state", "idle");
        String label = f.isNull("job_label") ? "" : f.optString("job_label");
        String tail = f.isNull("job_tail") ? "" : f.optString("job_tail");
        boolean running = "running".equals(state);

        String text;
        if ("idle".equals(state)) {
            text = "대기 중";
        } else if (running) {
            text = String.format(Locale.US, "%s 진행 중 (%.0f초)\n%s",
                    label, f.optDouble("job_elapsed_s", 0), tail);
        } else {
            // A finished job is history, so stamp it with age and stop painting
            // it red once it is stale. The live readiness badge above is the
            // authority on what the robot is doing now -- a failed bring-up from
            // ten minutes ago must not keep contradicting it after the operator
            // has fixed the underlying problem by hand.
            double age = f.optDouble("job_age_s", 0);
            String when = age < 60 ? String.format(Locale.US, "%.0f초 전", age)
                                   : String.format(Locale.US, "%.0f분 전", age / 60.0);
            text = "succeeded".equals(state)
                    ? String.format(Locale.US, "마지막 기동: %s 완료 (%s)\n%s", label, when, tail)
                    : String.format(Locale.US, "마지막 기동: %s 실패 코드 %d (%s)\n%s",
                            label, f.optInt("job_exit_code", -1), when, tail);
        }
        boolean freshFailure = "failed".equals(state) && f.optDouble("job_age_s", 0) <= 120;
        jobStatusText.setText(text);
        jobStatusText.setTextColor(ContextCompat.getColor(this,
                freshFailure ? R.color.danger_red
                             : running ? R.color.text_primary : R.color.text_secondary));

        // Only offer what this NUC actually has, and never queue a second job.
        JSONObject avail = f.optJSONObject("jobs_available");
        boolean hasStack = avail != null && avail.optBoolean("stack", false);
        stackStartButton.setEnabled(hasStack && !running);
        jobCancelButton.setEnabled(running);
    }

    private void setupBackNavigation() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                moveTaskToBack(true);
            }
        });
    }

    private void startTelemetryPolling() {
        mainHandler.post(telemetryPollRunnable);
    }

    private void setupDefaultPresets() {
        presetsById = new JSONObject();
        putPreset(createPreset(PRESET_BASE, getString(R.string.base_preset_name), "assist", 3));
        putPreset(createPreset(PRESET_FAST, getString(R.string.fast_preset_name), "autonomous", 5));
        putPreset(createPreset(PRESET_SLOW, getString(R.string.slow_preset_name), "manual", 1));
    }

    private void loadLocalPresets() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        SharedPreferences preferences = presetPreferences();
        defaultPresetId = preferences.getString(userId + "_default", "");
        String rawPresets = preferences.getString(userId + "_presets", "");
        if (rawPresets == null || rawPresets.isEmpty()) {
            return;
        }

        try {
            JSONArray presets = new JSONArray(rawPresets);
            for (int i = 0; i < presets.length(); i++) {
                JSONObject preset = presets.optJSONObject(i);
                if (preset != null && isKnownPresetId(preset.optString("id", ""))) {
                    putPreset(createPreset(
                            preset.optString("id", ""),
                            preset.optString("name", defaultPresetName(preset.optString("id", ""))),
                            preset.optString("mode", "assist"),
                            preset.optInt("step_level", defaultPresetStep(preset.optString("id", "")))
                    ));
                }
            }
        } catch (JSONException ignored) {
            // Corrupt local preset cache falls back to built-in defaults.
        }
    }

    private void saveLocalPresets() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        presetPreferences()
                .edit()
                .putString(userId + "_presets", presetsArray().toString())
                .putString(userId + "_default", defaultPresetId)
                .apply();
    }

    private SharedPreferences presetPreferences() {
        return getSharedPreferences(PRESET_PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void loadPresets() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        apiClient.getUserPresets(userId, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    readPresetsResponse(response.optJSONObject("presets_data"));
                    saveLocalPresets();
                    renderPresetCards();
                    applyDefaultPresetIfNeeded();
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> {
                    loadLocalPresets();
                    renderPresetCards();
                    applyDefaultPresetIfNeeded();
                });
            }
        });
    }

    private void readPresetsResponse(JSONObject presetsData) {
        if (presetsData == null) {
            return;
        }

        defaultPresetId = presetsData.optString("default_preset_id", "");
        JSONArray presets = presetsData.optJSONArray("presets");
        if (presets == null) {
            return;
        }

        for (int i = 0; i < presets.length(); i++) {
            JSONObject preset = presets.optJSONObject(i);
            if (preset != null && isKnownPresetId(preset.optString("id", ""))) {
                putPreset(createPreset(
                        preset.optString("id", ""),
                        preset.optString("name", defaultPresetName(preset.optString("id", ""))),
                        preset.optString("mode", "assist"),
                        preset.optInt("step_level", defaultPresetStep(preset.optString("id", "")))
                ));
            }
        }
    }

    private void applyDefaultPresetIfNeeded() {
        if (defaultPresetApplied || defaultPresetId.isEmpty()) {
            return;
        }
        defaultPresetApplied = true;
        applyPreset(defaultPresetId, true);
    }

    private void renderPresetCards() {
        renderPresetCard(PRESET_BASE, presetBaseCard, presetBaseNameText, presetBaseSummaryText);
        renderPresetCard(PRESET_FAST, presetFastCard, presetFastNameText, presetFastSummaryText);
        renderPresetCard(PRESET_SLOW, presetSlowCard, presetSlowNameText, presetSlowSummaryText);
    }

    private void renderPresetCard(String presetId, MaterialCardView card, TextView nameText, TextView summaryText) {
        JSONObject preset = presetForId(presetId);
        boolean isActivePreset = presetId.equals(activePresetId);
        boolean isDefaultPreset = presetId.equals(defaultPresetId);
        nameText.setText(preset.optString("name", defaultPresetName(presetId)));
        summaryText.setText(presetSummary(preset, isDefaultPreset, isActivePreset));
        card.setStrokeWidth(isActivePreset ? 4 : isDefaultPreset ? 3 : 1);
        card.setStrokeColor(ContextCompat.getColor(
                this,
                isActivePreset || isDefaultPreset ? R.color.brand_blue : R.color.control_stroke
        ));
        card.setCardBackgroundColor(ContextCompat.getColor(
                this,
                isActivePreset ? R.color.preset_active_background
                        : isDefaultPreset ? R.color.preset_default_background
                        : R.color.card_background
        ));
    }

    private String presetSummary(JSONObject preset, boolean isDefaultPreset, boolean isActivePreset) {
        StringBuilder builder = new StringBuilder();
        builder.append(modeDisplayName(preset.optString("mode", "")));
        builder.append('\n');
        builder.append(preset.optInt("step_level", 3)).append(" 단계");
        if (isActivePreset) {
            builder.append(" / 선택됨");
        }
        if (isDefaultPreset) {
            builder.append(" / ").append(getString(R.string.preset_default_badge));
        }
        return builder.toString();
    }

    private void showPresetDialog(String presetId) {
        JSONObject preset = presetForId(presetId);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(4);
        container.setPadding(padding, padding, padding, 0);

        EditText nameEditText = new EditText(this);
        nameEditText.setHint(R.string.preset_name_hint);
        nameEditText.setSingleLine(true);
        nameEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        nameEditText.setText(preset.optString("name", defaultPresetName(presetId)));
        container.addView(nameEditText, matchWrapParams());

        TextView modeLabel = dialogLabel("주행 모드");
        container.addView(modeLabel, matchWrapParamsWithTop(14));

        Spinner modeSpinner = new Spinner(this);
        ArrayAdapter<String> modeAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                PRESET_MODE_LABELS
        );
        modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(modeAdapter);
        modeSpinner.setSelection(modeIndex(preset.optString("mode", "assist")));
        container.addView(modeSpinner, matchWrapParams());

        TextView stepLabel = dialogLabel("Step Level");
        container.addView(stepLabel, matchWrapParamsWithTop(14));

        Spinner stepSpinner = new Spinner(this);
        ArrayAdapter<String> stepAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                PRESET_STEP_LABELS
        );
        stepAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        stepSpinner.setAdapter(stepAdapter);
        stepSpinner.setSelection(stepIndex(preset.optInt("step_level", 3)));
        container.addView(stepSpinner, matchWrapParams());

        CheckBox defaultCheckBox = new CheckBox(this);
        defaultCheckBox.setText(R.string.preset_default_checkbox);
        defaultCheckBox.setChecked(presetId.equals(defaultPresetId));
        container.addView(defaultCheckBox, matchWrapParamsWithTop(12));

        new AlertDialog.Builder(this)
                .setTitle(R.string.preset_dialog_title)
                .setView(container)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.preset_save, (dialog, which) -> {
                    String name = nameEditText.getText().toString().trim();
                    if (name.isEmpty()) {
                        name = defaultPresetName(presetId);
                    }
                    String mode = PRESET_MODE_VALUES[modeSpinner.getSelectedItemPosition()];
                    int selectedStepLevel = stepSpinner.getSelectedItemPosition() + 1;
                    savePresetFromDialog(presetId, name, mode, selectedStepLevel, defaultCheckBox.isChecked());
                })
                .show();
    }

    private void savePresetFromDialog(
            String presetId,
            String name,
            String mode,
            int selectedStepLevel,
            boolean useAsDefault
    ) {
        JSONObject preset = createPreset(presetId, name, mode, selectedStepLevel);
        putPreset(preset);
        if (useAsDefault) {
            defaultPresetId = presetId;
        } else if (presetId.equals(defaultPresetId)) {
            defaultPresetId = "";
        }
        renderPresetCards();
        saveLocalPresets();
        savePresetsToJetson();
        applyPreset(presetId, false);
    }

    private void savePresetsToJetson() {
        String userId = UserSession.userId(this);
        if (userId.isEmpty()) {
            return;
        }

        apiClient.saveUserPresets(userId, presetsArray(), defaultPresetId, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> showStatus("프리셋 저장 완료"));
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus("프리셋 저장 실패: " + message));
            }
        });
    }

    private void applyPreset(String presetId, boolean fromDefault) {
        if (!isKnownPresetId(presetId)) {
            return;
        }
        activePresetId = presetId;
        renderPresetCards();
        JSONObject preset = presetForId(presetId);
        String mode = preset.optString("mode", "assist");
        int presetStepLevel = preset.optInt("step_level", 3);
        applyPresetLocally(mode, presetStepLevel);

        String prefix = fromDefault ? "기본 프리셋 적용: " : "프리셋 적용: ";
        String presetName = preset.optString("name", defaultPresetName(presetId));
        sendModeAndStepCommands(mode, presetStepLevel, prefix + presetName);
    }

    private void applyPresetLocally(String mode, int presetStepLevel) {
        stepLevel = clampStepLevel(presetStepLevel);
        if (stepLevelText != null) {
            stepLevelText.setText(getString(R.string.step_level_value, stepLevel));
        }
        updateDisplayedMode(mode);
        syncModeSelection(mode);
    }

    private JSONArray presetsArray() {
        JSONArray result = new JSONArray();
        for (String presetId : PRESET_IDS) {
            result.put(presetForId(presetId));
        }
        return result;
    }

    private JSONObject presetForId(String presetId) {
        JSONObject preset = presetsById.optJSONObject(presetId);
        if (preset != null) {
            return preset;
        }
        return createPreset(presetId, defaultPresetName(presetId), defaultPresetMode(presetId), defaultPresetStep(presetId));
    }

    private JSONObject createPreset(String presetId, String name, String mode, int selectedStepLevel) {
        JSONObject preset = new JSONObject();
        try {
            preset.put("id", presetId);
            preset.put("name", name);
            preset.put("mode", normalizePresetMode(mode));
            preset.put("step_level", clampStepLevel(selectedStepLevel));
        } catch (JSONException ignored) {
            // JSONObject with stable preset values cannot fail here.
        }
        return preset;
    }

    private void putPreset(JSONObject preset) {
        try {
            presetsById.put(preset.optString("id", ""), preset);
        } catch (JSONException ignored) {
            // JSONObject with a nested object cannot fail here.
        }
    }

    private boolean isKnownPresetId(String presetId) {
        for (String knownPresetId : PRESET_IDS) {
            if (knownPresetId.equals(presetId)) {
                return true;
            }
        }
        return false;
    }

    private String defaultPresetName(String presetId) {
        switch (presetId) {
            case PRESET_FAST:
                return getString(R.string.fast_preset_name);
            case PRESET_SLOW:
                return getString(R.string.slow_preset_name);
            case PRESET_BASE:
            default:
                return getString(R.string.base_preset_name);
        }
    }

    private String defaultPresetMode(String presetId) {
        switch (presetId) {
            case PRESET_FAST:
                return "autonomous";
            case PRESET_SLOW:
                return "manual";
            case PRESET_BASE:
            default:
                return "assist";
        }
    }

    private int defaultPresetStep(String presetId) {
        switch (presetId) {
            case PRESET_FAST:
                return 5;
            case PRESET_SLOW:
                return 1;
            case PRESET_BASE:
            default:
                return 3;
        }
    }

    private String normalizePresetMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.US);
        for (String presetMode : PRESET_MODE_VALUES) {
            if (presetMode.equals(normalizedMode)) {
                return presetMode;
            }
        }
        return "assist";
    }

    private int modeIndex(String mode) {
        String normalizedMode = normalizePresetMode(mode);
        for (int i = 0; i < PRESET_MODE_VALUES.length; i++) {
            if (PRESET_MODE_VALUES[i].equals(normalizedMode)) {
                return i;
            }
        }
        return 1;
    }

    private int stepIndex(int selectedStepLevel) {
        return Math.max(0, Math.min(4, selectedStepLevel - 1));
    }

    private int clampStepLevel(int selectedStepLevel) {
        return Math.max(1, Math.min(5, selectedStepLevel));
    }

    private String modeDisplayName(String mode) {
        switch (normalizePresetMode(mode)) {
            case "manual":
                return "수동";
            case "autonomous":
                return "자율주행";
            case "assist":
            default:
                return "어시스트";
        }
    }

    private TextView dialogLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        label.setTextSize(14);
        return label;
    }

    private LinearLayout.LayoutParams matchWrapParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapParamsWithTop(int topMarginDp) {
        LinearLayout.LayoutParams params = matchWrapParams();
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 100);
    }

    private void checkApiConnection() {
        setConnectingUi();
        apiClient.getHealth(new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    setConnectedUi(true);
                    rawJsonText.setText("Jetson API 연결됨\n버전: "
                            + response.optString("version", "--")
                            + "\nROS 토픽 수신을 확인하는 중입니다.");
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> {
                    setConnectedUi(false);
                    showStatus(message);
                });
            }
        });
    }

    private void pollTelemetry() {
        apiClient.getTelemetry(new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    setConnectedUi(true);
                    renderTelemetry(response.optJSONObject("telemetry"));
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> setConnectedUi(false));
            }
        });
    }

    private void renderTelemetry(JSONObject telemetry) {
        if (telemetry == null) {
            rawJsonText.setText("Jetson API 연결됨\n텔레메트리 응답이 비어 있습니다.");
            systemStateText.setText(R.string.system_state_waiting);
            return;
        }
        Double speedKph = readDouble(telemetry, "speed_kph");
        Integer batteryPercent = readInteger(telemetry, "battery_percent");
        String mode = readString(telemetry, "mode");
        boolean hasTelemetry = speedKph != null || batteryPercent != null || !mode.trim().isEmpty();

        speedMpsText.setText(speedKph == null ? "--" : speedFormat.format(speedKph));
        speedText.setText(speedKph == null ? getString(R.string.empty_speed_mps) : speedFormat.format(speedKph / 3.6) + " m/s");
        updateDisplayedMode(mode);
        systemStateText.setText(hasTelemetry ? R.string.system_state_running : R.string.system_state_waiting);
        rawJsonText.setText(telemetryStatusText(speedKph, batteryPercent, displayModeForTelemetry(mode), telemetry));
        syncModeSelection(displayModeForTelemetry(mode));
    }

    private void openCameraStream() {
        Intent intent = new Intent(this, CameraActivity.class);
        intent.putExtra(CameraActivity.EXTRA_STREAM_URL, AppConfig.CAMERA_STREAM_URL);
        startActivity(intent);
    }

    private void sendModeCommand(String mode) {
        activePresetId = "";
        renderPresetCards();
        updateDisplayedMode(mode);
        syncModeSelection(mode);
        JSONObject payload = new JSONObject();
        try {
            payload.put("command", "set_mode");
            payload.put("mode", mode);
            payload.put("source", "android_wifi");
            payload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("명령 생성에 실패했습니다.");
            return;
        }
        sendCommand(payload, "모드 전환 요청: " + displayMode(mode));
    }

    private void setStepLevel(int nextStepLevel) {
        if (nextStepLevel < 1 || nextStepLevel > 5) {
            return;
        }
        activePresetId = "";
        renderPresetCards();
        stepLevel = nextStepLevel;
        if (stepLevelText != null) {
            stepLevelText.setText(getString(R.string.step_level_value, stepLevel));
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("command", "set_step");
            payload.put("step_level", stepLevel);
            payload.put("source", "android_wifi");
            payload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("단계 명령 생성에 실패했습니다.");
            return;
        }
        sendCommand(payload, "단계 전환 요청: " + stepLevel);
    }

    private void sendModeAndStepCommands(String mode, int presetStepLevel, String successMessage) {
        JSONObject modePayload = new JSONObject();
        JSONObject stepPayload = new JSONObject();
        int requestedStepLevel = clampStepLevel(presetStepLevel);
        try {
            modePayload.put("command", "set_mode");
            modePayload.put("mode", mode);
            modePayload.put("source", "android_preset");
            modePayload.put("user_id", UserSession.userId(this));

            stepPayload.put("command", "set_step");
            stepPayload.put("step_level", requestedStepLevel);
            stepPayload.put("source", "android_preset");
            stepPayload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("프리셋 명령 생성에 실패했습니다.");
            return;
        }

        rawJsonText.setText("명령 전송 중: " + successMessage);
        apiClient.sendCommand(modePayload, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                apiClient.sendCommand(stepPayload, new WheelchairApiClient.JsonCallback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        runOnUiThread(() -> {
                            rawJsonText.setText(successMessage);
                            showStatus(successMessage);
                        });
                    }

                    @Override
                    public void onError(String message, Throwable throwable) {
                        runOnUiThread(() -> showStatus("프리셋 단계 적용 실패: " + message));
                    }
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus("프리셋 모드 적용 실패: " + message));
            }
        });
    }

    private void sendStopCommand() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("command", "stop");
            payload.put("source", "android_wifi");
            payload.put("user_id", UserSession.userId(this));
        } catch (JSONException exception) {
            showStatus("정지 명령 생성에 실패했습니다.");
            return;
        }
        systemStateText.setText(R.string.system_state_stop_requested);
        sendCommand(payload, "정지 명령을 보냈습니다.");
    }

    private void sendCommand(JSONObject payload, String successMessage) {
        rawJsonText.setText("명령 전송 중: " + commandSummary(payload));
        if (bluetoothClient != null && bluetoothClient.isConnected()) {
            // "Sent" is all we can honestly claim here: sendJsonCommand only queues
            // the write. The bridge's {"type":"ack"} reply is what confirms it, and
            // onLineReceived surfaces that.
            bluetoothClient.sendJsonCommand(payload);
            rawJsonText.setText("BT 명령 전송함 (NUC 응답 대기): " + commandSummary(payload));
            showStatus("Bluetooth: " + successMessage);
            return;
        }
        if (AppConfig.BLUETOOTH_ONLY) {
            // No silent HTTP fallback: on this build there is no server at the other
            // end, so a "sent" toast for a STOP press would be a lie.
            rawJsonText.setText("명령 미전송 — 블루투스가 끊겨 있습니다.");
            showStatus("블루투스 연결이 끊겼습니다. 명령이 전송되지 않았습니다.");
            setConnectedUi(false);
            return;
        }
        apiClient.sendCommand(payload, new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                runOnUiThread(() -> {
                    rawJsonText.setText(successMessage);
                    showStatus(successMessage);
                });
            }

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> showStatus(message));
            }
        });
    }

    private void syncModeSelection(String mode) {
        if (modeToggleGroup == null) {
            return;   // drive mode is a readout in this build, not a selector
        }
        int checkedButtonId = modeButtonId(mode);
        if (checkedButtonId == 0 || checkedButtonId == modeToggleGroup.getCheckedButtonId()) {
            return;
        }
        updatingModeSelection = true;
        modeToggleGroup.check(checkedButtonId);
        updatingModeSelection = false;
    }

    private void setConnectedUi(boolean connected) {
        connectButton.setEnabled(true);
        connectButton.setText(R.string.connection_check);
        if (connected) {
            setConnectionState(
                    getString(R.string.connection_connected),
                    R.drawable.status_dot_connected,
                    R.color.status_connected_text
            );
        } else {
            setConnectionState(
                    getString(R.string.connection_disconnected),
                    R.drawable.status_dot_disconnected,
                    R.color.status_disconnected_text
            );
        }
    }

    private void setConnectingUi() {
        setConnectionState(
                getString(R.string.connection_connecting),
                R.drawable.status_dot_connecting,
                R.color.status_connecting_text
        );
    }

    private void setConnectionState(String text, int badgeBackgroundResId, int textColorResId) {
        connectionStateText.setText(text);
        connectionStateText.setTextColor(ContextCompat.getColor(this, textColorResId));
        bluetoothBadgeText.setBackgroundResource(badgeBackgroundResId);
    }

    private void updateDisplayedMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if (!normalizedMode.isEmpty()) {
            lastDisplayedMode = normalizedMode;
        }

        String visibleMode = lastDisplayedMode == null ? "" : lastDisplayedMode.trim();
        modeText.setText(visibleMode.isEmpty() ? "--" : displayMode(visibleMode).toUpperCase(Locale.US));
    }

    private String displayModeForTelemetry(String mode) {
        String normalizedMode = mode == null ? "" : mode.trim();
        if (!normalizedMode.isEmpty()) {
            return normalizedMode;
        }
        return lastDisplayedMode == null ? "" : lastDisplayedMode;
    }

    private String displayMode(String mode) {
        String normalizedMode = mode == null ? "" : mode.toLowerCase(Locale.US);
        switch (normalizedMode) {
            case "manual":
            case "manual_mode":
                return "수동 모드";
            case "assist":
            case "assist_mode":
                return "어시스트 모드";
            case "autonomous":
            case "autonomous_mode":
            case "auto":
                return "자율주행 모드";
            default:
                return mode;
        }
    }

    private String batteryStatusText(Integer batteryPercent) {
        if (batteryPercent == null) {
            return getString(R.string.empty_status);
        }
        return getString(batteryPercent >= 30 ? R.string.battery_status_normal : R.string.battery_status_low);
    }

    /**
     * The mode selector is gone: drive mode belongs to uart.py and is shown as a
     * readout. Kept as a no-op so the surviving preset code paths still compile.
     */
    private int modeButtonId(String mode) {
        return 0;
    }

    private Double readDouble(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optDouble(key);
    }

    private Integer readInteger(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return null;
        }
        return object.optInt(key);
    }

    private String readString(JSONObject object, String key) {
        if (!object.has(key) || object.isNull(key)) {
            return "";
        }
        return object.optString(key, "");
    }

    private String telemetryStatusText(Double speedKph, Integer batteryPercent, String mode, JSONObject telemetry) {
        boolean hasTelemetry = speedKph != null || batteryPercent != null || !mode.trim().isEmpty();
        if (!hasTelemetry) {
            return "Jetson API 연결됨\n"
                    + "ROS 토픽 수신 대기 중입니다.\n"
                    + "속도: /cmd_vel 또는 /wheelchair/speed_kph 대기\n"
                    + "배터리: /wheelchair/battery_percent 또는 /battery/soc 대기\n"
                    + "모드: /wheelchair/mode 대기";
        }

        String updatedAt = readString(telemetry, "updated_at");
        StringBuilder builder = new StringBuilder();
        builder.append("Jetson API 연결됨\n");
        builder.append("속도: ").append(speedKph == null ? "--" : speedFormat.format(speedKph) + " km/h").append('\n');
        builder.append("배터리: ").append(batteryPercent == null ? "--" : batteryPercent + "%").append('\n');
        builder.append("모드: ").append(mode.trim().isEmpty() ? "--" : displayMode(mode)).append('\n');
        builder.append("마지막 수신: ").append(updatedAt.isEmpty() ? "--" : updatedAt);
        return builder.toString();
    }

    private String commandSummary(JSONObject payload) {
        String command = payload.optString("command", "");
        if ("set_mode".equals(command)) {
            return "모드 전환 " + displayMode(payload.optString("mode", ""));
        }
        if ("set_step".equals(command)) {
            return "단계 전환 " + payload.optInt("step_level") + "단계";
        }
        if ("stop".equals(command)) {
            return "정지";
        }
        return command;
    }

    private void resetScrollPosition() {
        dashboardScrollView.post(() -> dashboardScrollView.scrollTo(0, 0));
    }

    private void showStatus(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        rawJsonText.setText(message);
    }

    private WheelchairApiClient.JsonCallback emptyCallback() {
        return new WheelchairApiClient.JsonCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                // Usage logs are best-effort.
            }

            @Override
            public void onError(String message, Throwable throwable) {
                // Usage logs are best-effort.
            }
        };
    }
}
