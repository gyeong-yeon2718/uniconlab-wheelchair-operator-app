package com.example.jetsonbtmonitor;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.ParcelUuid;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BluetoothWheelchairClient {
    private static final UUID SERIAL_PORT_PROFILE_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final int[] DIRECT_RFCOMM_CHANNELS = {1, 2, 3, 4, 5};

    public interface Callback {
        void onConnected(BluetoothDevice device);
        void onDisconnected();
        void onLineReceived(String line);
        void onError(String message, Throwable throwable);

        /**
         * A reconnect attempt is queued. Default no-op so the device picker,
         * which finishes as soon as it is connected, need not care.
         */
        default void onReconnecting(int attempt, long delayMs) {
        }
    }

    private static BluetoothWheelchairClient sharedClient;

    public static synchronized BluetoothWheelchairClient getSharedInstance(Callback callback) {
        if (sharedClient == null) {
            sharedClient = new BluetoothWheelchairClient(callback);
        } else if (callback != null) {
            sharedClient.setCallback(callback);
        }
        return sharedClient;
    }

    public static synchronized BluetoothWheelchairClient getSharedInstance() {
        return sharedClient;
    }

    private Callback callback;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Object socketLock = new Object();
    private final Object writeLock = new Object();

    private BluetoothSocket socket;
    private volatile boolean running;
    private BluetoothDevice connectedDevice;

    // Reconnect state. The link drops for ordinary reasons in the field -- the
    // operator walks around a corner, or the bridge is restarted on the NUC --
    // and before this the only way back was to leave the dashboard, return to the
    // device picker and connect by hand. That is a bad thing to be doing while
    // watching a chair move.
    private static final long RECONNECT_FIRST_DELAY_MS = 2000L;
    private static final long RECONNECT_MAX_DELAY_MS = 15000L;
    private volatile boolean autoReconnect;
    private volatile BluetoothDevice lastDevice;
    private final AtomicBoolean reconnectInFlight = new AtomicBoolean(false);

    public BluetoothWheelchairClient(Callback callback) {
        this.callback = callback;
    }

    public synchronized void setCallback(Callback callback) {
        this.callback = callback;
    }

    public boolean isConnected() {
        synchronized (socketLock) {
            return socket != null && socket.isConnected() && running;
        }
    }

    public BluetoothDevice getConnectedDevice() {
        return connectedDevice;
    }

    public void connect(BluetoothDevice device) {
        autoReconnect = false;          // stop any retry loop aimed at the old device
        closeLink();
        this.connectedDevice = device;
        this.lastDevice = device;
        this.autoReconnect = true;
        attempt(device, 0);
    }

    /**
     * One connection attempt; on failure it schedules the next itself.
     *
     * @param attemptNumber 0 for the operator's own press, ≥1 for a retry, which
     *                      is what decides whether a failure is reported as an
     *                      error or as a quiet "still trying".
     */
    private void attempt(BluetoothDevice device, int attemptNumber) {
        executor.execute(() -> {
            try {
                BluetoothSocket nextSocket = connectSocket(device, attemptNumber == 0);
                synchronized (socketLock) {
                    socket = nextSocket;
                    running = true;
                }
                reconnectInFlight.set(false);
                if (callback != null) {
                    callback.onConnected(device);
                }
                readLoop(nextSocket);
            } catch (IOException | SecurityException exception) {
                closeCurrentSocket();
                if (attemptNumber == 0 && callback != null) {
                    callback.onError("Bluetooth 연결에 실패했습니다: " + exception.getMessage(), exception);
                }
                reconnectInFlight.set(false);
                scheduleReconnect(attemptNumber + 1);
            }
        });
    }

    /**
     * Back off 2s, 4s, 8s, then every 15s for as long as the operator leaves the
     * dashboard open. Retrying flat out would hammer SDP and the radio; giving up
     * would strand a dashboard that is one step out of range from working again.
     */
    private void scheduleReconnect(int attemptNumber) {
        BluetoothDevice device = lastDevice;
        if (!autoReconnect || device == null) {
            return;
        }
        if (!reconnectInFlight.compareAndSet(false, true)) {
            return;                     // a retry is already queued
        }
        long delay = Math.min(RECONNECT_FIRST_DELAY_MS << Math.min(attemptNumber - 1, 3),
                              RECONNECT_MAX_DELAY_MS);
        if (callback != null) {
            callback.onReconnecting(attemptNumber, delay);
        }
        executor.execute(() -> {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                reconnectInFlight.set(false);
                return;
            }
            if (!autoReconnect || lastDevice == null) {
                reconnectInFlight.set(false);
                return;
            }
            attempt(lastDevice, attemptNumber);
        });
    }

    /**
     * SDP lookup first; blind channel probing only as a fallback.
     *
     * The order used to be reversed, and that was not a preference but a
     * correctness bug. {@code createRfcommSocket(channel)} asks for a channel
     * NUMBER, not a service, and the NUC also advertises HSP/HFP
     * ({@code SPP,HSP,AudioSource,AudioSink,Avrcp,HSP_AG}). Probing channels
     * 1..5 therefore attaches to whichever service sits on the first channel
     * that accepts -- observed landing on Hands-Free, which promptly sent
     * {@code AT+VGS=15} while the app cheerfully reported "연결됨".
     *
     * {@code createRfcommSocketToServiceRecord} asks for the SPP UUID by name, so
     * it can only reach the bridge. The bridge registers a real SDP record via
     * org.bluez.Profile1, so this path works; the reflection fallback remains only
     * for a NUC running the older raw-bind transport, which publishes no record.
     */
    /**
     * Force a fresh SDP query before trusting the cached service list.
     *
     * Android caches a remote device's UUIDs from the last SDP fetch. The NUC
     * only advertises SPP while the bridge is running, so any query made while
     * the bridge was down caches a list with no SPP -- and then
     * createRfcommSocketToServiceRecord keeps failing even after the bridge comes
     * back, silently falling through to the channel probe and landing on
     * Hands-Free. Re-fetching is what makes the UUID path usable in practice.
     */
    private void refreshServiceRecords(BluetoothDevice device) {
        try {
            if (hasSerialPortUuid(device)) {
                return;
            }
            device.fetchUuidsWithSdp();
            // The result arrives via an ACTION_UUID broadcast; polling getUuids()
            // is enough here and keeps this class free of a receiver lifecycle.
            for (int i = 0; i < 12; i++) {
                Thread.sleep(250);
                if (hasSerialPortUuid(device)) {
                    return;
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (SecurityException ignored) {
            // BLUETOOTH_CONNECT withdrawn; the connect below reports it properly.
        }
    }

    private boolean hasSerialPortUuid(BluetoothDevice device) {
        try {
            ParcelUuid[] uuids = device.getUuids();
            if (uuids == null) {
                return false;
            }
            for (ParcelUuid uuid : uuids) {
                if (SERIAL_PORT_PROFILE_UUID.equals(uuid.getUuid())) {
                    return true;
                }
            }
        } catch (SecurityException ignored) {
        }
        return false;
    }

    /**
     * @param allowChannelProbe whether to fall back to blind RFCOMM channel
     *     probing when the SPP service record cannot be reached. Only ever true
     *     for a connection the operator asked for. On an automatic retry it is a
     *     trap: while the bridge is down the NUC still advertises Hands-Free, the
     *     probe attaches to it, and the dashboard reports 연결됨 over a socket
     *     that answers with AT+VGS=15. Observed exactly that on 2026-08-23.
     */
    private BluetoothSocket connectSocket(BluetoothDevice device, boolean allowChannelProbe)
            throws IOException {
        refreshServiceRecords(device);
        IOException lastException;
        BluetoothSocket uuidSocket = null;
        try {
            uuidSocket = device.createRfcommSocketToServiceRecord(SERIAL_PORT_PROFILE_UUID);
            uuidSocket.connect();
            return uuidSocket;
        } catch (IOException exception) {
            lastException = exception;
            closeQuietly(uuidSocket);
        }

        if (!allowChannelProbe) {
            throw lastException;
        }

        Method method;
        try {
            method = device.getClass().getMethod("createRfcommSocket", int.class);
        } catch (NoSuchMethodException exception) {
            method = null;      // hidden-API enforcement on newer Android
        }
        if (method == null) {
            throw lastException;
        }

        for (int channel : DIRECT_RFCOMM_CHANNELS) {
            BluetoothSocket directChannelSocket = null;
            try {
                directChannelSocket = (BluetoothSocket) method.invoke(device, channel);
                directChannelSocket.connect();
                return directChannelSocket;
            } catch (IllegalAccessException | InvocationTargetException exception) {
                IOException wrapped = new IOException("RFCOMM direct channel connection failed.", exception);
                wrapped.addSuppressed(lastException);
                closeQuietly(directChannelSocket);
                throw wrapped;
            } catch (IOException exception) {
                exception.addSuppressed(lastException);
                lastException = exception;
                closeQuietly(directChannelSocket);
            }
        }
        throw lastException;
    }

    public void sendJsonCommand(JSONObject json) {
        if (json != null) {
            sendLine(json.toString());
        }
    }

    public void sendLine(String line) {
        executor.execute(() -> {
            BluetoothSocket currentSocket;
            synchronized (socketLock) {
                currentSocket = socket;
            }
            if (currentSocket == null || !currentSocket.isConnected()) {
                if (callback != null) {
                    callback.onError("NUC에 블루투스로 연결되어 있지 않습니다.", null);
                }
                return;
            }

            try {
                synchronized (writeLock) {
                    OutputStream outputStream = currentSocket.getOutputStream();
                    outputStream.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                }
            } catch (IOException exception) {
                if (callback != null) {
                    callback.onError("블루투스 명령 전송 실패", exception);
                }
                disconnect();
            }
        });
    }

    /**
     * Drop the socket but keep trying to get it back.
     *
     * A dead bridge does not necessarily fault the RFCOMM socket: the ACL link
     * stays up and {@code readLine()} blocks forever, so the dashboard sits there
     * looking connected with telemetry frozen at whatever arrived last. Something
     * watching the data has to break that, and closing the socket is what makes
     * the read loop unwind into the normal reconnect path.
     */
    public void forceReconnect() {
        closeCurrentSocket();
    }

    /** Operator-initiated: stays disconnected until someone connects again. */
    public void disconnect() {
        autoReconnect = false;
        lastDevice = null;
        closeLink();
    }

    private void closeLink() {
        running = false;
        closeCurrentSocket();
        this.connectedDevice = null;
    }

    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }

    private void readLoop(BluetoothSocket activeSocket) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(activeSocket.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (running && (line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    if (callback != null) {
                        callback.onLineReceived(line.trim());
                    }
                }
            }
        } catch (IOException exception) {
            if (running && callback != null) {
                callback.onError("블루투스 수신 중 연결이 끊겼습니다.", exception);
            }
        } finally {
            closeCurrentSocket();
            if (callback != null) {
                callback.onDisconnected();
            }
            // The dashboard has already been blanked by onDisconnected, so the
            // retry cannot leave stale numbers on screen looking live.
            scheduleReconnect(1);
        }
    }

    private void closeCurrentSocket() {
        BluetoothSocket currentSocket;
        synchronized (socketLock) {
            currentSocket = socket;
            socket = null;
        }

        if (currentSocket != null) {
            try {
                currentSocket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeQuietly(BluetoothSocket socketToClose) {
        if (socketToClose == null) {
            return;
        }
        try {
            socketToClose.close();
        } catch (IOException ignored) {
        }
    }
}
