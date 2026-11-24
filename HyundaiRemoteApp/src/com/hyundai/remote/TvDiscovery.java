package com.hyundai.remote;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TvDiscovery {
    private static final String TAG = "TvDiscovery";

    public static class DiscoveredTv {
        public String ip;
        public String friendlyName;
        public String modelNumber;

        public DiscoveredTv(String ip, String friendlyName, String modelNumber) {
            this.ip = ip;
            this.friendlyName = friendlyName;
            this.modelNumber = modelNumber;
        }
    }

    public interface DiscoveryCallback {
        void onDeviceFound(DiscoveredTv tv);
        void onScanComplete();
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());

    public static void scan(final String baseSubnet, final DiscoveryCallback callback) {
        new Thread(() -> {
            // First check known IP 192.168.1.3 quickly
            checkIp("192.168.1.3", callback);

            // SSDP broadcast search
            try {
                String ssdpMsg = "M-SEARCH * HTTP/1.1\r\n" +
                        "HOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\n" +
                        "MX: 2\r\n" +
                        "ST: urn:lge-com:service:webos-second-screen:1\r\n\r\n";

                DatagramSocket socket = new DatagramSocket();
                socket.setSoTimeout(1500);
                byte[] sendData = ssdpMsg.getBytes();
                DatagramPacket packet = new DatagramPacket(sendData, sendData.length,
                        InetAddress.getByName("239.255.255.250"), 1900);
                socket.send(packet);

                byte[] buf = new byte[2048];
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 2000) {
                    try {
                        DatagramPacket rcv = new DatagramPacket(buf, buf.length);
                        socket.receive(rcv);
                        String ip = rcv.getAddress().getHostAddress();
                        checkIp(ip, callback);
                    } catch (Exception ignored) {
                        break;
                    }
                }
                socket.close();
            } catch (Exception e) {
                Log.w(TAG, "SSDP broadcast failed: " + e.getMessage());
            }

            handler.post(callback::onScanComplete);
        }).start();
    }

    public static void checkIp(String ip, DiscoveryCallback callback) {
        try {
            // Check if port 3001 is open
            Socket s = new Socket();
            s.connect(new InetSocketAddress(ip, 3001), 600);
            s.close();

            // Fetch UPnP descriptor from port 1885
            String friendlyName = "Hyundai webOS TV";
            String modelNumber = "HYLED4026WiM";

            try {
                URL url = new URL("http://" + ip + ":1885/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(800);
                conn.setReadTimeout(800);
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
                in.close();
                String xml = sb.toString();

                Matcher mName = Pattern.compile("<friendlyName>(.*?)</friendlyName>").matcher(xml);
                if (mName.find()) friendlyName = mName.group(1);

                Matcher mModel = Pattern.compile("<modelNumber>(.*?)</modelNumber>").matcher(xml);
                if (mModel.find()) modelNumber = mModel.group(1);
            } catch (Exception ignored) {}

            DiscoveredTv tv = new DiscoveredTv(ip, friendlyName, modelNumber);
            handler.post(() -> callback.onDeviceFound(tv));
        } catch (Exception ignored) {}
    }
}
