package com.hyundai.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class WebOsController {
    private static final String TAG = "WebOsController";

    public interface Listener {
        void onConnected();
        void onRegistered(String clientKey);
        void onDisconnected();
        void onVolumeReceived(int volume, boolean muted);
        void onForegroundApp(String appId);
        void onError(String error);
    }

    private WebSocketClient mainClient;
    private WebSocketClient pointerClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger reqId = new AtomicInteger(1);
    private Listener listener;

    private String currentIp;
    private String clientKey;
    private boolean isRegistered = false;

    public WebOsController(Listener listener) {
        this.listener = listener;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public synchronized boolean isConnected() {
        return mainClient != null && mainClient.isOpen() && isRegistered;
    }

    public synchronized void connect(String ip, String savedKey) {
        disconnect();
        this.currentIp = ip;
        this.clientKey = savedKey;

        new Thread(() -> {
            try {
                URI uri = new URI("wss://" + ip + ":3001");
                mainClient = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        Log.d(TAG, "Connected to webOS TV at " + ip);
                        sendRegistration();
                    }

                    @Override
                    public void onMessage(String message) {
                        handleMessage(message);
                    }

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        Log.d(TAG, "Connection closed: " + reason);
                        isRegistered = false;
                        closePointer();
                        post(() -> {
                            if (listener != null) listener.onDisconnected();
                        });
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e(TAG, "WebSocket error: " + ex.getMessage());
                        post(() -> {
                            if (listener != null) listener.onError("Error: " + ex.getMessage());
                        });
                    }
                };

                // Bypass SSL check for TV local self-signed certificate
                mainClient.setSocketFactory(createTrustAllSslSocketFactory());
                mainClient.setConnectionLostTimeout(10);
                mainClient.connect();

            } catch (Exception e) {
                Log.e(TAG, "Failed to connect", e);
                post(() -> {
                    if (listener != null) listener.onError("Fallo de conexión: " + e.getMessage());
                });
            }
        }).start();
    }

    public synchronized void disconnect() {
        isRegistered = false;
        closePointer();
        if (mainClient != null) {
            try {
                mainClient.close();
            } catch (Exception ignored) {}
            mainClient = null;
        }
    }

    private void closePointer() {
        if (pointerClient != null) {
            try {
                pointerClient.close();
            } catch (Exception ignored) {}
            pointerClient = null;
        }
    }

    private void sendRegistration() {
        try {
            JSONObject manifest = new JSONObject();
            manifest.put("appVersion", "1.0");
            manifest.put("manifestVersion", 1);
            JSONArray perms = new JSONArray();
            perms.put("LAUNCH");
            perms.put("LAUNCH_WEBAPP");
            perms.put("APP_TO_APP");
            perms.put("CLOSE");
            perms.put("CONTROL_AUDIO");
            perms.put("CONTROL_DISPLAY");
            perms.put("CONTROL_INPUT_TEXT");
            perms.put("CONTROL_MOUSE_AND_KEYBOARD");
            perms.put("READ_INSTALLED_APPS");
            perms.put("READ_CURRENT_CHANNEL");
            perms.put("READ_RUNNING_APPS");
            manifest.put("permissions", perms);

            JSONObject payload = new JSONObject();
            payload.put("forcePairing", false);
            payload.put("pairingType", "PROMPT");
            if (clientKey != null && !clientKey.isEmpty()) {
                payload.put("client-key", clientKey);
            }
            payload.put("manifest", manifest);

            JSONObject reg = new JSONObject();
            reg.put("type", "register");
            reg.put("id", "register_req");
            reg.put("payload", payload);

            if (mainClient != null && mainClient.isOpen()) {
                mainClient.send(reg.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error sending registration", e);
        }
    }

    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type");

            if ("registered".equals(type)) {
                JSONObject payload = json.optJSONObject("payload");
                if (payload != null) {
                    clientKey = payload.optString("client-key");
                }
                isRegistered = true;
                post(() -> {
                    if (listener != null) {
                        listener.onConnected();
                        if (clientKey != null) listener.onRegistered(clientKey);
                    }
                });

                requestPointerSocket();
                requestVolume();
                requestForegroundApp();

            } else if ("response".equals(type)) {
                JSONObject payload = json.optJSONObject("payload");
                if (payload != null) {
                    if (payload.has("volumeStatus")) {
                        JSONObject vs = payload.getJSONObject("volumeStatus");
                        int vol = vs.optInt("volume", 0);
                        boolean muted = vs.optBoolean("muteStatus", false);
                        post(() -> {
                            if (listener != null) listener.onVolumeReceived(vol, muted);
                        });
                    } else if (payload.has("volume")) {
                        int vol = payload.optInt("volume", 0);
                        boolean muted = payload.optBoolean("muted", false);
                        post(() -> {
                            if (listener != null) listener.onVolumeReceived(vol, muted);
                        });
                    }
                    if (payload.has("socketPath")) {
                        String socketPath = payload.getString("socketPath");
                        connectPointerSocket(socketPath);
                    }
                    if (payload.has("appId")) {
                        String appId = payload.getString("appId");
                        post(() -> {
                            if (listener != null) listener.onForegroundApp(appId);
                        });
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing webOS message: " + message, e);
        }
    }

    private void requestPointerSocket() {
        sendRequest("ssap://com.webos.service.networkinput/getPointerInputSocket", null);
    }

    private void connectPointerSocket(String socketPath) {
        new Thread(() -> {
            try {
                URI uri = new URI(socketPath);
                pointerClient = new WebSocketClient(uri) {
                    @Override
                    public void onOpen(ServerHandshake handshakedata) {
                        Log.d(TAG, "Pointer socket open!");
                    }

                    @Override
                    public void onMessage(String message) {}

                    @Override
                    public void onClose(int code, String reason, boolean remote) {
                        Log.d(TAG, "Pointer socket closed");
                    }

                    @Override
                    public void onError(Exception ex) {
                        Log.e(TAG, "Pointer socket error", ex);
                    }
                };
                pointerClient.setSocketFactory(createTrustAllSslSocketFactory());
                pointerClient.connect();
            } catch (Exception e) {
                Log.e(TAG, "Error opening pointer socket", e);
            }
        }).start();
    }

    public void sendKey(String key) {
        if (pointerClient != null && pointerClient.isOpen()) {
            String command = "type:button\nname:" + key + "\n\n";
            pointerClient.send(command);
        } else {
            try {
                JSONObject payload = new JSONObject();
                payload.put("key", key);
                sendRequest("ssap://system/simulateButton", payload);
            } catch (Exception ignored) {}
        }
    }

    // --- KEYBOARD & TEXT SEARCH INPUT ---
    public void insertText(String text) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("text", text);
            payload.put("replace", 0);
            sendRequest("ssap://com.webos.service.ime/insertText", payload);
        } catch (Exception ignored) {}
    }

    public void deleteCharacters(int count) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("count", count);
            sendRequest("ssap://com.webos.service.ime/deleteCharacters", payload);
        } catch (Exception ignored) {}
    }

    public void sendEnterKey() {
        sendRequest("ssap://com.webos.service.ime/sendEnterKey", null);
    }

    public void sendPowerOff() {
        sendRequest("ssap://system/turnOff", null);
    }

    public void volumeUp() {
        sendRequest("ssap://audio/volumeUp", null);
        requestVolume();
    }

    public void volumeDown() {
        sendRequest("ssap://audio/volumeDown", null);
        requestVolume();
    }

    public void setMute(boolean mute) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("mute", mute);
            sendRequest("ssap://audio/setMute", payload);
            requestVolume();
        } catch (Exception ignored) {}
    }

    public void channelUp() {
        sendRequest("ssap://tv/channelUp", null);
    }

    public void channelDown() {
        sendRequest("ssap://tv/channelDown", null);
    }

    public void play() {
        sendRequest("ssap://media.controls/play", null);
    }

    public void pause() {
        sendRequest("ssap://media.controls/pause", null);
    }

    public void rewind() {
        sendRequest("ssap://media.controls/rewind", null);
    }

    public void fastForward() {
        sendRequest("ssap://media.controls/fastForward", null);
    }

    public void launchApp(String appId) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("id", appId);
            sendRequest("ssap://system.launcher/launch", payload);
        } catch (Exception ignored) {}
    }

    public void switchInput(String inputId) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("inputId", inputId);
            sendRequest("ssap://tv/switchInput", payload);
        } catch (Exception ignored) {}
    }

    public void requestVolume() {
        sendRequest("ssap://audio/getVolume", null);
    }

    public void requestForegroundApp() {
        sendRequest("ssap://com.webos.applicationManager/getForegroundAppInfo", null);
    }

    private void sendRequest(String uri, JSONObject payload) {
        if (mainClient == null || !mainClient.isOpen()) return;
        try {
            JSONObject req = new JSONObject();
            req.put("id", "req_" + reqId.getAndIncrement());
            req.put("type", "request");
            req.put("uri", uri);
            if (payload != null) {
                req.put("payload", payload);
            }
            mainClient.send(req.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error sending request: " + uri, e);
        }
    }

    private void post(Runnable r) {
        mainHandler.post(r);
    }

    private static SSLSocketFactory createTrustAllSslSocketFactory() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                }
            };
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustAllCerts, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
