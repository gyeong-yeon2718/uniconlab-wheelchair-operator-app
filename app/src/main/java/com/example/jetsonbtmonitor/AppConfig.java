package com.example.jetsonbtmonitor;

final class AppConfig {
    private AppConfig() {
    }

    /**
     * UniconLab NUC build: Bluetooth SPP is the only transport, so every Wi-Fi
     * HTTP path (telemetry polling, presets, the background service, the camera
     * stream) is switched off. Set this back to false to build the original
     * Jetson/Wi-Fi variant, which still needs the URLs below.
     */
    static final boolean BLUETOOTH_ONLY = true;

    /** The NUC has no camera server; keep the dashboard button hidden. */
    static final boolean CAMERA_ENABLED = false;

    static final String WHEELCHAIR_WIFI_SSID = "WheelchairNUC";

    // Only used when BLUETOOTH_ONLY is false (Jetson Wi-Fi hotspot deployment).
    static final String JETSON_API_BASE_URL = "http://192.168.12.1:8081";
    static final String NUC_API_BASE_URL = "http://192.168.12.1:8081";
    static final String CAMERA_STREAM_URL = "http://192.168.12.1:8080/stream";
}
