package com.hyundai.remote;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RemoteWebServer {
    private static final String TAG = "RemoteWebServer";
    public static final int DEFAULT_PORT = 8080;

    public interface WebCommandHandler {
        void onCommand(String action);
        void onLaunchApp(String appId);
        void onSwitchInput(String inputId);
        void onInsertText(String text, boolean enter);
        JSONObject getStatus();
    }

    private final Context context;
    private final WebCommandHandler handler;
    private int port = DEFAULT_PORT;
    private ServerSocket serverSocket;
    private boolean isRunning = false;
    private ExecutorService threadPool;
    private byte[] cachedHtml;

    public RemoteWebServer(Context context, WebCommandHandler handler) {
        this.context = context.getApplicationContext();
        this.handler = handler;
    }

    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        new Thread(() -> {
            try {
                try {
                    serverSocket = new ServerSocket(port);
                } catch (Exception e) {
                    port = 8088;
                    serverSocket = new ServerSocket(port);
                }
                Log.i(TAG, "Web Remote server listening on port " + port);

                while (isRunning && !serverSocket.isClosed()) {
                    Socket clientSocket = serverSocket.accept();
                    if (!isRunning) break;
                    threadPool.execute(() -> handleClient(clientSocket));
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.e(TAG, "Server socket exception: " + e.getMessage());
                }
            }
        }).start();
    }

    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    public int getPort() {
        return port;
    }

    public boolean isRunning() {
        return isRunning;
    }

    private void handleClient(Socket socket) {
        try (socket;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String path = parts[1];

            // Read headers
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            // Read body if POST
            String body = "";
            if (contentLength > 0) {
                char[] bodyChars = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int r = reader.read(bodyChars, read, contentLength - read);
                    if (r == -1) break;
                    read += r;
                }
                body = new String(bodyChars, 0, read);
            }

            // Route request
            if ("OPTIONS".equals(method)) {
                sendOptionsResponse(out);
            } else if (("GET".equals(method) || "HEAD".equals(method)) && ("/".equals(path) || path.startsWith("/index"))) {
                serveHtml(out, "HEAD".equals(method));
            } else if ("GET".equals(method) && path.startsWith("/api/status")) {
                JSONObject status = handler.getStatus();
                sendJsonResponse(out, 200, status.toString());
            } else if ("POST".equals(method) && path.startsWith("/api/command")) {
                JSONObject json = new JSONObject(body.isEmpty() ? "{}" : body);
                String action = json.optString("action", "");
                handler.onCommand(action);
                JSONObject status = handler.getStatus();
                sendJsonResponse(out, 200, status.toString());
            } else if ("POST".equals(method) && path.startsWith("/api/launch")) {
                JSONObject json = new JSONObject(body.isEmpty() ? "{}" : body);
                String appId = json.optString("appId", "");
                handler.onLaunchApp(appId);
                JSONObject status = handler.getStatus();
                sendJsonResponse(out, 200, status.toString());
            } else if ("POST".equals(method) && path.startsWith("/api/input")) {
                JSONObject json = new JSONObject(body.isEmpty() ? "{}" : body);
                String inputId = json.optString("inputId", "");
                handler.onSwitchInput(inputId);
                JSONObject status = handler.getStatus();
                sendJsonResponse(out, 200, status.toString());
            } else if ("POST".equals(method) && path.startsWith("/api/text")) {
                JSONObject json = new JSONObject(body.isEmpty() ? "{}" : body);
                String text = json.optString("text", "");
                boolean enter = json.optBoolean("enter", false);
                handler.onInsertText(text, enter);
                JSONObject status = handler.getStatus();
                sendJsonResponse(out, 200, status.toString());
            } else {
                sendNotFound(out);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling client request", e);
        }
    }

    private void serveHtml(OutputStream out, boolean headOnly) throws Exception {
        byte[] htmlBytes = getHtmlBytes();
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + htmlBytes.length + "\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        if (!headOnly) {
            out.write(htmlBytes);
        }
        out.flush();
    }

    private synchronized byte[] getHtmlBytes() {
        if (cachedHtml != null) return cachedHtml;
        try (InputStream is = context.getResources().openRawResource(R.raw.remote_web);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            cachedHtml = baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load raw HTML resource, using fallback", e);
            cachedHtml = "<html><body><h1>Hyundai Remote</h1><p>Error cargando interfaz web.</p></body></html>"
                    .getBytes(StandardCharsets.UTF_8);
        }
        return cachedHtml;
    }

    private void sendJsonResponse(OutputStream out, int statusCode, String json) throws Exception {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + statusCode + " OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + bytes.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
    }

    private void sendOptionsResponse(OutputStream out) throws Exception {
        String resp = "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type\r\n" +
                "Connection: close\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendNotFound(OutputStream out) throws Exception {
        String msg = "{\"error\": \"Not Found\"}";
        sendJsonResponse(out, 404, msg);
    }

    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            // Prioritize wlan0 / wifi
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().toLowerCase().contains("wlan")) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
            // Fallback to any non-loopback IPv4
            for (NetworkInterface intf : interfaces) {
                for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }
}
