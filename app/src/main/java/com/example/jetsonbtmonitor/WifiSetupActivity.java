package com.example.jetsonbtmonitor;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class WifiSetupActivity extends AppCompatActivity {
    private static final int REQ_BT_PERMISSIONS = 101;
    private static final String PREF_LAST_BT_MAC = "last_bt_device_mac";

    private Spinner bluetoothDeviceSpinner;
    private TextView wifiStatusText;
    private MaterialButton checkWifiButton;

    private BluetoothAdapter bluetoothAdapter;
    private List<BluetoothDevice> pairedDeviceList = new ArrayList<>();
    private List<String> deviceDisplayNames = new ArrayList<>();
    private ArrayAdapter<String> spinnerAdapter;

    private BluetoothWheelchairClient btClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_setup);

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        bindViews();
        setupActions();
        checkPermissionsAndLoadDevices();
    }

    private void bindViews() {
        wifiStatusText = findViewById(R.id.wifiStatusText);
        MaterialButton openWifiSettingsButton = findViewById(R.id.openWifiSettingsButton);
        checkWifiButton = findViewById(R.id.checkWifiButton);

        // Customize button texts for Bluetooth
        if (openWifiSettingsButton != null) {
            openWifiSettingsButton.setText("블루투스 페어링 설정");
        }
        if (checkWifiButton != null) {
            checkWifiButton.setText("블루투스 1터치 연결");
        }

        // The adapter must be built and attached whenever the spinner exists. The
        // previous "only build it if the spinner is null" guard left the visible
        // spinner permanently empty, so getSelectedItemPosition() returned
        // INVALID_POSITION and the connect button fell back to index 0 of an
        // unordered bonded-device Set -- i.e. it connected to whichever paired
        // device happened to come first, earbuds included.
        spinnerAdapter = new ArrayAdapter<>(this, R.layout.item_bt_device, deviceDisplayNames);
        spinnerAdapter.setDropDownViewResource(R.layout.item_bt_device_dropdown);

        bluetoothDeviceSpinner = findViewById(R.id.bluetoothDeviceSpinner);
        if (bluetoothDeviceSpinner != null) {
            bluetoothDeviceSpinner.setAdapter(spinnerAdapter);
        }
    }

    private void setupActions() {
        MaterialButton openWifiSettingsButton = findViewById(R.id.openWifiSettingsButton);
        if (openWifiSettingsButton != null) {
            openWifiSettingsButton.setOnClickListener(v -> openBluetoothSettings());
        }

        checkWifiButton.setOnClickListener(v -> connectToSelectedBluetoothDevice());
    }

    private void checkPermissionsAndLoadDevices() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN
                }, REQ_BT_PERMISSIONS);
                return;
            }
        }
        loadPairedBluetoothDevices();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BT_PERMISSIONS && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPairedBluetoothDevices();
        } else {
            wifiStatusText.setText("블루투스 권한이 필요합니다.");
        }
    }

    private void loadPairedBluetoothDevices() {
        pairedDeviceList.clear();
        deviceDisplayNames.clear();

        if (bluetoothAdapter == null) {
            wifiStatusText.setText("이 기기는 블루투스를 지원하지 않습니다.");
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            wifiStatusText.setText("블루투스가 꺼져 있습니다. 블루투스를 켜주세요.");
            return;
        }

        try {
            Set<BluetoothDevice> paired = bluetoothAdapter.getBondedDevices();
            if (paired != null && !paired.isEmpty()) {
                // getBondedDevices() is an unordered Set, so sort by address to get a
                // stable list; otherwise the same device moves between app launches.
                List<BluetoothDevice> sorted = new ArrayList<>(paired);
                Collections.sort(sorted, (a, b) -> a.getAddress().compareTo(b.getAddress()));
                for (BluetoothDevice dev : sorted) {
                    pairedDeviceList.add(dev);
                    String name = dev.getName() != null ? dev.getName() : "이름 없음";
                    deviceDisplayNames.add(name + " (" + dev.getAddress() + ")");
                }
                wifiStatusText.setText("페어링된 블루투스 기기 " + pairedDeviceList.size() + "개 감지됨");
            } else {
                wifiStatusText.setText("페어링된 블루투스 기기가 없습니다. [블루투스 페어링 설정]을 눌러 NUC와 페어링해 주세요.");
            }
        } catch (SecurityException e) {
            wifiStatusText.setText("블루투스 권한 오류가 발생했습니다.");
        }

        if (spinnerAdapter != null) {
            spinnerAdapter.notifyDataSetChanged();
        }
        preselectLastUsedDevice();
    }

    /** Makes "1-touch connect" mean the NUC you used last, not an arbitrary Set element. */
    private void preselectLastUsedDevice() {
        if (bluetoothDeviceSpinner == null || pairedDeviceList.isEmpty()) {
            return;
        }
        String lastMac = getSharedPreferences("wheelchair_bt_prefs", MODE_PRIVATE)
                .getString(PREF_LAST_BT_MAC, "");
        if (lastMac == null || lastMac.isEmpty()) {
            return;
        }
        for (int i = 0; i < pairedDeviceList.size(); i++) {
            if (lastMac.equals(pairedDeviceList.get(i).getAddress())) {
                bluetoothDeviceSpinner.setSelection(i);
                return;
            }
        }
    }

    private void openBluetoothSettings() {
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        startActivity(intent);
    }

    private void connectToSelectedBluetoothDevice() {
        if (pairedDeviceList.isEmpty()) {
            Toast.makeText(this, "연결할 블루투스 기기가 없습니다.", Toast.LENGTH_SHORT).show();
            loadPairedBluetoothDevices();
            return;
        }

        int selectedIndex = 0;
        if (bluetoothDeviceSpinner != null) {
            selectedIndex = bluetoothDeviceSpinner.getSelectedItemPosition();
        }
        if (selectedIndex < 0 || selectedIndex >= pairedDeviceList.size()) {
            selectedIndex = 0;
        }

        BluetoothDevice targetDevice = pairedDeviceList.get(selectedIndex);
        wifiStatusText.setText("NUC 블루투스 연결 중: " + targetDevice.getName());

        btClient = BluetoothWheelchairClient.getSharedInstance(new BluetoothWheelchairClient.Callback() {
            @Override
            public void onConnected(BluetoothDevice device) {
                runOnUiThread(() -> {
                    wifiStatusText.setText("NUC 블루투스 연결 성공!");
                    SharedPreferences prefs = getSharedPreferences("wheelchair_bt_prefs", MODE_PRIVATE);
                    try {
                        prefs.edit().putString(PREF_LAST_BT_MAC, device.getAddress()).apply();
                    } catch (SecurityException ignored) {}

                    Toast.makeText(WifiSetupActivity.this, "NUC 블루투스 연결 완료", Toast.LENGTH_SHORT).show();

                    Intent intent = new Intent(WifiSetupActivity.this, MainActivity.class);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> wifiStatusText.setText("블루투스 연결 해제됨"));
            }

            @Override
            public void onLineReceived(String line) {}

            @Override
            public void onError(String message, Throwable throwable) {
                runOnUiThread(() -> wifiStatusText.setText("연결 실패: " + message));
            }
        });

        btClient.connect(targetDevice);
    }
}
