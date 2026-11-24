package com.hyundai.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

public class MainActivity extends Activity implements WebOsController.Listener, RemoteWebServer.WebCommandHandler {

    private static final String PREFS_NAME = "HyundaiRemotePrefs";
    private static final String PREF_MODE = "remote_mode"; // "IR" or "WIFI"
    private static final String PREF_PROFILE = "ir_profile";
    private static final String PREF_IP = "last_ip";
    private static final String PREF_KEY = "client_key";
    private static final String DEFAULT_IP = "192.168.1.3";
    private static final String DEFAULT_KEY = "a57b50c45610a3b701c9f3a195d32696";

    private IrTransmitter irTransmitter;
    private WebOsController webOsController;
    private RemoteWebServer remoteWebServer;
    private SharedPreferences prefs;
    private Vibrator vibrator;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private boolean isIrMode = true; // Default to IR as requested!

    private TextView tvDeviceName;
    private TextView tvDeviceModel;
    private TextView tvStatus;
    private TextView tvVolumeDisplay;
    private Button btnModeIr;
    private Button btnModeWifi;
    private LinearLayout layoutNumpad;

    // Web Server UI
    private TextView tvWebStatus;
    private TextView tvWebUrl;
    private Button btnOpenBrowser;
    private Button btnCopyUrl;
    private String webUrl = "";

    private boolean isMuted = false;
    private int currentVolume = 36;
    private String currentIp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        irTransmitter = new IrTransmitter(this);
        webOsController = new WebOsController(this);

        currentIp = prefs.getString(PREF_IP, DEFAULT_IP);
        String savedMode = prefs.getString(PREF_MODE, "IR");
        isIrMode = !"WIFI".equals(savedMode);

        int profileIdx = prefs.getInt(PREF_PROFILE, 0);
        if (profileIdx >= 0 && profileIdx < IrTransmitter.Profile.values().length) {
            irTransmitter.setProfile(IrTransmitter.Profile.values()[profileIdx]);
        }

        initViews();
        setupListeners();
        updateModeUi();
        initWebServer();
    }

    private void initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name);
        tvDeviceModel = findViewById(R.id.tv_device_model);
        tvStatus = findViewById(R.id.tv_status);
        tvVolumeDisplay = findViewById(R.id.tv_volume_display);
        btnModeIr = findViewById(R.id.btn_mode_ir);
        btnModeWifi = findViewById(R.id.btn_mode_wifi);
        layoutNumpad = findViewById(R.id.layout_numpad);

        tvWebStatus = findViewById(R.id.tv_web_status);
        tvWebUrl = findViewById(R.id.tv_web_url);
        btnOpenBrowser = findViewById(R.id.btn_open_browser);
        btnCopyUrl = findViewById(R.id.btn_copy_url);
    }

    private void initWebServer() {
        remoteWebServer = new RemoteWebServer(this, this);
        remoteWebServer.start();

        handler.postDelayed(() -> {
            String ip = RemoteWebServer.getLocalIpAddress();
            int port = remoteWebServer.getPort();
            webUrl = "http://" + ip + ":" + port;
            if (tvWebUrl != null) {
                tvWebUrl.setText(webUrl);
            }
            if (tvWebStatus != null) {
                tvWebStatus.setText("● Servidor Activo (Puerto " + port + ")");
            }
        }, 300);
    }

    private void updateModeUi() {
        if (isIrMode) {
            btnModeIr.setBackgroundResource(R.drawable.btn_accent);
            btnModeIr.setTextColor(getColor(R.color.text_white));
            btnModeWifi.setBackgroundColor(0);
            btnModeWifi.setTextColor(getColor(R.color.text_muted));

            if (irTransmitter.hasIrEmitter()) {
                tvStatus.setText("● Emisor IR Xiaomi Activo (38 kHz)");
                tvStatus.setTextColor(getColor(R.color.status_green));
                tvDeviceModel.setText("Hyundai HYLED4026WiM (" + irTransmitter.getCurrentProfile().name + ")");
            } else {
                tvStatus.setText("⚠ Emisor IR no detectado en hardware");
                tvStatus.setTextColor(getColor(R.color.status_red));
            }
            tvVolumeDisplay.setText("VOLUMEN (IR)");
        } else {
            btnModeWifi.setBackgroundResource(R.drawable.btn_accent);
            btnModeWifi.setTextColor(getColor(R.color.text_white));
            btnModeIr.setBackgroundColor(0);
            btnModeIr.setTextColor(getColor(R.color.text_muted));

            tvDeviceModel.setText("HYLED4026WiM (" + currentIp + ")");
            tvStatus.setText("○ Conectando por Wi-Fi...");
            tvStatus.setTextColor(getColor(R.color.text_muted));

            String savedKey = prefs.getString(PREF_KEY, DEFAULT_KEY);
            webOsController.connect(currentIp, savedKey);
        }
    }

    private void setupListeners() {
        // MODE TOGGLE
        btnModeIr.setOnClickListener(v -> {
            haptic();
            isIrMode = true;
            prefs.edit().putString(PREF_MODE, "IR").apply();
            webOsController.disconnect();
            updateModeUi();
            Toast.makeText(this, "Modo Infrarrojo Activado", Toast.LENGTH_SHORT).show();
        });

        btnModeWifi.setOnClickListener(v -> {
            haptic();
            isIrMode = false;
            prefs.edit().putString(PREF_MODE, "WIFI").apply();
            updateModeUi();
            Toast.makeText(this, "Modo Wi-Fi Activado", Toast.LENGTH_SHORT).show();
        });

        // WEB SERVER BUTTONS
        if (btnOpenBrowser != null) {
            btnOpenBrowser.setOnClickListener(v -> {
                haptic();
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl.isEmpty() ? "http://localhost:8080" : webUrl));
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "No se pudo abrir el navegador", Toast.LENGTH_SHORT).show();
                }
            });
        }

        if (btnCopyUrl != null) {
            btnCopyUrl.setOnClickListener(v -> {
                haptic();
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText("Hyundai TV Remote Web", webUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(this, "Enlace copiado: " + webUrl, Toast.LENGTH_SHORT).show();
                }
            });
        }

        // KEYBOARD & SEARCH
        findViewById(R.id.btn_keyboard).setOnClickListener(v -> {
            haptic();
            showKeyboardDialog();
        });

        // POWER
        setCommand(R.id.btn_power, IrTransmitter.CMD_POWER, () -> webOsController.sendPowerOff());

        // D-PAD
        setCommand(R.id.btn_dpad_up, IrTransmitter.CMD_UP, () -> webOsController.sendKey("UP"));
        setCommand(R.id.btn_dpad_down, IrTransmitter.CMD_DOWN, () -> webOsController.sendKey("DOWN"));
        setCommand(R.id.btn_dpad_left, IrTransmitter.CMD_LEFT, () -> webOsController.sendKey("LEFT"));
        setCommand(R.id.btn_dpad_right, IrTransmitter.CMD_RIGHT, () -> webOsController.sendKey("RIGHT"));
        setCommand(R.id.btn_dpad_ok, IrTransmitter.CMD_OK, () -> webOsController.sendKey("ENTER"));

        // SYSTEM NAV
        setCommand(R.id.btn_back, IrTransmitter.CMD_BACK, () -> webOsController.sendKey("BACK"));
        setCommand(R.id.btn_home, IrTransmitter.CMD_HOME, () -> webOsController.sendKey("HOME"));
        setCommand(R.id.btn_menu, IrTransmitter.CMD_MENU, () -> webOsController.sendKey("MENU"));
        setCommand(R.id.btn_exit, IrTransmitter.CMD_EXIT, () -> webOsController.sendKey("EXIT"));
        setCommand(R.id.btn_input, IrTransmitter.CMD_INPUT, () -> webOsController.switchInput("com.webos.app.hdmi3"));

        // VOLUME & CHANNEL
        setContinuousCommand(R.id.btn_vol_up, IrTransmitter.CMD_VOL_UP, () -> webOsController.volumeUp());
        setContinuousCommand(R.id.btn_vol_down, IrTransmitter.CMD_VOL_DOWN, () -> webOsController.volumeDown());
        setCommand(R.id.btn_mute, IrTransmitter.CMD_MUTE, () -> webOsController.setMute(!isMuted));
        setCommand(R.id.btn_ch_up, IrTransmitter.CMD_CH_UP, () -> webOsController.channelUp());
        setCommand(R.id.btn_ch_down, IrTransmitter.CMD_CH_DOWN, () -> webOsController.channelDown());

        // QUICK ACCESS: NETFLIX, YOUTUBE, HDMI 3
        findViewById(R.id.btn_quick_netflix).setOnClickListener(v -> {
            onLaunchApp("netflix");
            Toast.makeText(this, "Abriendo Netflix...", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_quick_youtube).setOnClickListener(v -> {
            onLaunchApp("youtube.leanback.v4");
            Toast.makeText(this, "Abriendo YouTube...", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.btn_quick_hdmi3).setOnClickListener(v -> {
            onSwitchInput("com.webos.app.hdmi3");
            Toast.makeText(this, "Cambiando a HDMI 3...", Toast.LENGTH_SHORT).show();
        });

        // MEDIA
        setCommand(R.id.btn_play, IrTransmitter.CMD_PLAY, () -> webOsController.play());
        setCommand(R.id.btn_pause, IrTransmitter.CMD_PAUSE, () -> webOsController.pause());
        setCommand(R.id.btn_rewind, IrTransmitter.CMD_REWIND, () -> webOsController.rewind());
        setCommand(R.id.btn_forward, IrTransmitter.CMD_FAST_FORWARD, () -> webOsController.fastForward());

        // COLOR BUTTONS
        setCommand(R.id.btn_color_red, IrTransmitter.CMD_RED, null);
        setCommand(R.id.btn_color_green, IrTransmitter.CMD_GREEN, null);
        setCommand(R.id.btn_color_yellow, IrTransmitter.CMD_YELLOW, null);
        setCommand(R.id.btn_color_blue, IrTransmitter.CMD_BLUE, null);

        // NUMPAD TOGGLE
        findViewById(R.id.btn_toggle_numpad).setOnClickListener(v -> {
            haptic();
            if (layoutNumpad.getVisibility() == View.VISIBLE) {
                layoutNumpad.setVisibility(View.GONE);
            } else {
                layoutNumpad.setVisibility(View.VISIBLE);
            }
        });

        // NUMPAD DIGITS 0-9
        int[] numIds = {R.id.btn_num_0, R.id.btn_num_1, R.id.btn_num_2, R.id.btn_num_3,
                R.id.btn_num_4, R.id.btn_num_5, R.id.btn_num_6, R.id.btn_num_7, R.id.btn_num_8, R.id.btn_num_9};
        for (int i = 0; i <= 9; i++) {
            final int digit = i;
            View v = findViewById(numIds[i]);
            if (v != null) {
                v.setOnClickListener(view -> {
                    haptic();
                    if (isIrMode) {
                        irTransmitter.transmitNumber(digit);
                    } else {
                        webOsController.sendKey(String.valueOf(digit));
                    }
                });
            }
        }

        // SETTINGS / PROFILE DIALOG
        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            haptic();
            showSettingsDialog();
        });
    }

    private void executeRemoteCommand(int irCommand, Runnable wifiAction) {
        haptic();
        if (isIrMode) {
            boolean ok = irTransmitter.transmitCommand(irCommand);
            if (!ok && !irTransmitter.hasIrEmitter()) {
                Toast.makeText(this, "Emisor IR no disponible", Toast.LENGTH_SHORT).show();
            }
        } else if (wifiAction != null) {
            wifiAction.run();
        }
    }

    private void setCommand(int resId, int irCommand, Runnable wifiAction) {
        View v = findViewById(resId);
        if (v != null) {
            v.setOnClickListener(view -> executeRemoteCommand(irCommand, wifiAction));
        }
    }

    private void setContinuousCommand(int resId, int irCommand, Runnable wifiAction) {
        View v = findViewById(resId);
        if (v == null) return;

        final Runnable repeatRunnable = new Runnable() {
            @Override
            public void run() {
                executeRemoteCommand(irCommand, wifiAction);
                handler.postDelayed(this, 140);
            }
        };

        v.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.setPressed(true);
                    handler.removeCallbacks(repeatRunnable);
                    executeRemoteCommand(irCommand, wifiAction);
                    handler.postDelayed(repeatRunnable, 350);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.setPressed(false);
                    handler.removeCallbacks(repeatRunnable);
                    return true;
            }
            return false;
        });
    }

    private void haptic() {
        if (vibrator != null) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(20);
                }
            } catch (Exception ignored) {}
        }
    }

    private void showKeyboardDialog() {
        if (!webOsController.isConnected()) {
            String savedKey = prefs.getString(PREF_KEY, DEFAULT_KEY);
            webOsController.connect(currentIp, savedKey);
        }

        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_keyboard, null);

        EditText etSearch = dialogView.findViewById(R.id.et_search_text);
        Button btnBackspace = dialogView.findViewById(R.id.btn_dialog_backspace);
        Button btnClear = dialogView.findViewById(R.id.btn_dialog_clear);
        Button btnSend = dialogView.findViewById(R.id.btn_dialog_send);
        Button btnEnter = dialogView.findViewById(R.id.btn_dialog_enter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setNegativeButton("Cerrar", (d, w) -> {
                    InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
                })
                .create();

        dialog.show();

        etSearch.requestFocus();
        handler.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        }, 200);

        etSearch.setOnEditorActionListener((textView, actionId, keyEvent) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                String text = etSearch.getText().toString();
                sendTextAndEnter(text);
                dialog.dismiss();
                return true;
            }
            return false;
        });

        btnSend.setOnClickListener(v -> {
            haptic();
            String text = etSearch.getText().toString();
            if (!text.isEmpty()) {
                sendTextToTv(text);
                Toast.makeText(this, "Escribiendo en TV: " + text, Toast.LENGTH_SHORT).show();
            }
        });

        btnEnter.setOnClickListener(v -> {
            haptic();
            String text = etSearch.getText().toString();
            sendTextAndEnter(text);
            Toast.makeText(this, "Buscando: " + text, Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        btnBackspace.setOnClickListener(v -> {
            haptic();
            webOsController.deleteCharacters(1);
            String current = etSearch.getText().toString();
            if (current.length() > 0) {
                etSearch.setText(current.substring(0, current.length() - 1));
                etSearch.setSelection(etSearch.getText().length());
            }
        });

        btnClear.setOnClickListener(v -> {
            haptic();
            etSearch.setText("");
        });
    }

    private void sendTextToTv(String text) {
        if (!webOsController.isConnected()) {
            String savedKey = prefs.getString(PREF_KEY, DEFAULT_KEY);
            webOsController.connect(currentIp, savedKey);
            handler.postDelayed(() -> webOsController.insertText(text), 800);
        } else {
            webOsController.insertText(text);
        }
    }

    private void sendTextAndEnter(String text) {
        if (!text.isEmpty()) {
            sendTextToTv(text);
            handler.postDelayed(() -> webOsController.sendEnterKey(), 500);
        } else {
            webOsController.sendEnterKey();
        }
    }

    private void showSettingsDialog() {
        IrTransmitter.Profile[] profiles = IrTransmitter.Profile.values();
        String[] items = new String[profiles.length + 1];
        for (int i = 0; i < profiles.length; i++) {
            items[i] = profiles[i].name;
        }
        items[profiles.length] = "⚙ Configurar IP de TV (Wi-Fi)";

        new AlertDialog.Builder(this)
                .setTitle("Configuración de Control Remoto")
                .setItems(items, (dialog, which) -> {
                    if (which < profiles.length) {
                        irTransmitter.setProfile(profiles[which]);
                        prefs.edit().putInt(PREF_PROFILE, which).apply();
                        updateModeUi();
                        Toast.makeText(this, "Perfil IR cambiado a: " + profiles[which].name, Toast.LENGTH_SHORT).show();
                    } else {
                        showIpDialog();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showIpDialog() {
        final EditText input = new EditText(this);
        input.setText(currentIp);
        input.setPadding(32, 24, 32, 24);

        new AlertDialog.Builder(this)
                .setTitle("IP de Televisor Hyundai (Wi-Fi)")
                .setMessage("Ingresa la IP del televisor en tu red:")
                .setView(input)
                .setPositiveButton("Guardar", (d, w) -> {
                    String ip = input.getText().toString().trim();
                    if (!ip.isEmpty()) {
                        currentIp = ip;
                        prefs.edit().putString(PREF_IP, currentIp).apply();
                        if (!isIrMode) {
                            updateModeUi();
                        }
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    // ==========================================
    // RemoteWebServer.WebCommandHandler Implementation
    // ==========================================

    @Override
    public void onCommand(String action) {
        handler.post(() -> {
            haptic();
            // Automatically ensure Wi-Fi is connected if not connected
            if (!webOsController.isConnected()) {
                String savedKey = prefs.getString(PREF_KEY, DEFAULT_KEY);
                webOsController.connect(currentIp, savedKey);
            }

            switch (action) {
                case "POWER":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_POWER);
                    else webOsController.sendPowerOff();
                    break;
                case "UP":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_UP);
                    else webOsController.sendKey("UP");
                    break;
                case "DOWN":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_DOWN);
                    else webOsController.sendKey("DOWN");
                    break;
                case "LEFT":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_LEFT);
                    else webOsController.sendKey("LEFT");
                    break;
                case "RIGHT":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_RIGHT);
                    else webOsController.sendKey("RIGHT");
                    break;
                case "OK":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_OK);
                    else webOsController.sendKey("ENTER");
                    break;
                case "BACK":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_BACK);
                    else webOsController.sendKey("BACK");
                    break;
                case "HOME":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_HOME);
                    else webOsController.sendKey("HOME");
                    break;
                case "MENU":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_MENU);
                    else webOsController.sendKey("MENU");
                    break;
                case "EXIT":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_EXIT);
                    else webOsController.sendKey("EXIT");
                    break;
                case "VOL_UP":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_VOL_UP);
                    else webOsController.volumeUp();
                    break;
                case "VOL_DOWN":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_VOL_DOWN);
                    else webOsController.volumeDown();
                    break;
                case "MUTE":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_MUTE);
                    else webOsController.setMute(!isMuted);
                    break;
                case "CH_UP":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_CH_UP);
                    else webOsController.channelUp();
                    break;
                case "CH_DOWN":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_CH_DOWN);
                    else webOsController.channelDown();
                    break;
                case "PLAY":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_PLAY);
                    else webOsController.play();
                    break;
                case "PAUSE":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_PAUSE);
                    else webOsController.pause();
                    break;
                case "REWIND":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_REWIND);
                    else webOsController.rewind();
                    break;
                case "FAST_FORWARD":
                    if (isIrMode) irTransmitter.transmitCommand(IrTransmitter.CMD_FAST_FORWARD);
                    else webOsController.fastForward();
                    break;
                default:
                    if (action.startsWith("NUM_")) {
                        try {
                            int num = Integer.parseInt(action.substring(4));
                            if (isIrMode) irTransmitter.transmitNumber(num);
                            else webOsController.sendKey(String.valueOf(num));
                        } catch (Exception ignored) {}
                    }
                    break;
            }
        });
    }

    @Override
    public void onLaunchApp(String appId) {
        handler.post(() -> {
            haptic();
            if (!webOsController.isConnected()) {
                String savedKey = prefs.getString(PREF_KEY, DEFAULT_KEY);
                webOsController.connect(currentIp, savedKey);
                handler.postDelayed(() -> webOsController.launchApp(appId), 800);
            } else {
                webOsController.launchApp(appId);
            }
        });
    }

    @Override
    public void onSwitchInput(String inputId) {
        handler.post(() -> {
            haptic();
            if (!webOsController.isConnected()) {
                String savedKey = prefs.getString(PREF_KEY, DEFAULT_KEY);
                webOsController.connect(currentIp, savedKey);
                handler.postDelayed(() -> webOsController.switchInput(inputId), 800);
            } else {
                webOsController.switchInput(inputId);
            }
        });
    }

    @Override
    public void onInsertText(String text, boolean enter) {
        handler.post(() -> {
            haptic();
            if (enter) {
                sendTextAndEnter(text);
            } else {
                sendTextToTv(text);
            }
        });
    }

    @Override
    public JSONObject getStatus() {
        JSONObject json = new JSONObject();
        try {
            json.put("connected", webOsController != null && webOsController.isConnected());
            json.put("tvIp", currentIp);
            json.put("volume", currentVolume);
            json.put("muted", isMuted);
            json.put("mode", isIrMode ? "IR" : "WIFI");
        } catch (Exception ignored) {}
        return json;
    }

    // WebOsController Callbacks for Wi-Fi Mode
    @Override
    public void onConnected() {
        if (!isIrMode) {
            tvStatus.setText("● Conectado (Wi-Fi webOS)");
            tvStatus.setTextColor(getColor(R.color.status_green));
        }
    }

    @Override
    public void onRegistered(String newKey) {
        prefs.edit().putString(PREF_KEY, newKey).apply();
    }

    @Override
    public void onDisconnected() {
        if (!isIrMode) {
            tvStatus.setText("○ Desconectado de Wi-Fi");
            tvStatus.setTextColor(getColor(R.color.status_red));
        }
    }

    @Override
    public void onVolumeReceived(int volume, boolean muted) {
        this.currentVolume = volume;
        this.isMuted = muted;
        if (!isIrMode) {
            if (muted) {
                tvVolumeDisplay.setText("VOL: Silenciado");
                tvVolumeDisplay.setTextColor(getColor(R.color.status_red));
            } else {
                tvVolumeDisplay.setText("VOL: " + volume);
                tvVolumeDisplay.setTextColor(getColor(R.color.text_muted));
            }
        }
    }

    @Override
    public void onForegroundApp(String appId) {}

    @Override
    public void onError(String error) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (webOsController != null) {
            webOsController.disconnect();
        }
        if (remoteWebServer != null) {
            remoteWebServer.stop();
        }
    }
}
