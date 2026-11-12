package com.calendardesk.util;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 时间同步：NTP（SNTP over UDP）为主 + HTTP 兜底。
 *
 * - NTP 源：国内 NTP 服务器（UDP 123），精度可达几十毫秒级。
 * - HTTP 源：仅当 NTP 全部失败（例如网络封禁 UDP 123）时降级使用。
 *
 * 正确时间 = System.currentTimeMillis() + autoOffset + manualOffset
 * 北京时间 = 正确时间 + 8h（调用方用 GMT+8 Calendar 转换）
 */
public class TimeSyncHelper {

    // ---- NTP 配置 ----
    private static final String[] NTP_SERVERS = {
            "ntp.aliyun.com",
            "ntp.tencent.com",
            "cn.pool.ntp.org"
    };
    private static final int NTP_TIMEOUT_MS = 2000;
    private static final int NTP_PORT = 123;

    // NTP 纪元（1900-01-01）到 Unix 纪元（1970-01-01）的秒数
    private static final long NTP_EPOCH_OFFSET = 2208988800L;

    // ---- 投票配置 ----
    private static final double OUTLIER_THRESHOLD_MS = 2000;

    // ---- 状态 ----
    private static long autoOffset = 0;      // 校准的相位偏移
    private static boolean synced = false;
    private static boolean syncing = false;  // 防止并发同步
    private static String lastError = "";

    // ---- 单例 ----
    private static OkHttpClient client;

    private TimeSyncHelper() {}

    public static void init(OkHttpClient okClient) {
        client = okClient;
    }

    /**
     * 获取校准后的 UTC epoch ms。
     * 调用方用 GMT+8 Calendar 转换即可得到北京时间。
     */
    public static long getBeijingTimeMs(long manualOffset) {
        return System.currentTimeMillis() + autoOffset + manualOffset;
    }

    public static boolean isSynced() { return synced; }
    public static String getLastError() { return lastError; }

    /**
     * 异步执行一次完整同步（NTP 优先，HTTP 兜底）。
     * 结果通过 autoOffset 生效，下次 getBeijingTimeMs() 自动使用。
     */
    public static void syncAsync() {
        if (client == null) return;
        synchronized (TimeSyncHelper.class) {
            if (syncing) return;
            syncing = true;
        }

        new Thread(() -> {
            try {
                long offset = doSync();
                autoOffset = offset;
                synced = true;
                lastError = "";
            } catch (Exception e) {
                lastError = e.getMessage() != null ? e.getMessage() : "未知错误";
                synced = false;
            } finally {
                syncing = false;
            }
        }).start();
    }

    // ---- 内部实现 ----

    private static long doSync() throws Exception {
        // 1. NTP 采样（高精度，首选）
        List<Sample> ntpSamples = new ArrayList<>();
        for (String host : NTP_SERVERS) {
            try {
                Sample s = fetchNtp(host);
                if (s != null) ntpSamples.add(s);
            } catch (Exception ignored) {}
        }

        if (!ntpSamples.isEmpty()) {
            return combine(ntpSamples);
        }

        // 2. HTTP 兜底（NTP 全部失败时）
        List<Sample> httpSamples = new ArrayList<>();

        try {
            Sample s = fetchShijianOnline();
            if (s != null) httpSamples.add(s);
        } catch (Exception ignored) {}

        try {
            Sample s = fetchDateHeader("https://www.aliyun.com/");
            if (s != null) httpSamples.add(s);
        } catch (Exception ignored) {}

        try {
            Sample s = fetchDateHeader("https://www.baidu.com/");
            if (s != null) httpSamples.add(s);
        } catch (Exception ignored) {}

        try {
            Sample s = fetchDateHeader("https://www.qq.com/");
            if (s != null) httpSamples.add(s);
        } catch (Exception ignored) {}

        try {
            Sample s = fetchTimeApiIo();
            if (s != null) httpSamples.add(s);
        } catch (Exception ignored) {}

        if (httpSamples.isEmpty()) {
            throw new Exception("所有时间源均不可用");
        }
        return combine(httpSamples);
    }

    /**
     * 多源中位数投票 + 离群剔除 + 精度×RTT 加权平均。
     */
    private static long combine(List<Sample> samples) {
        // 按源分组，每个源取最低 RTT 样本
        Map<String, Sample> bySource = new LinkedHashMap<>();
        for (Sample s : samples) {
            Sample existing = bySource.get(s.source);
            if (existing == null || s.rtt < existing.rtt) {
                bySource.put(s.source, s);
            }
        }
        List<Sample> sourceSamples = new ArrayList<>(bySource.values());

        // 跨源中位数
        List<Long> offsets = new ArrayList<>();
        for (Sample s : sourceSamples) offsets.add(s.offset);
        Collections.sort(offsets);
        long median = offsets.get(offsets.size() / 2);

        // 剔除偏离中位数过大的离群源
        List<Sample> trusted = new ArrayList<>();
        for (Sample s : sourceSamples) {
            if (Math.abs(s.offset - median) <= OUTLIER_THRESHOLD_MS) {
                trusted.add(s);
            }
        }
        if (trusted.isEmpty()) {
            Sample closest = sourceSamples.get(0);
            long minDiff = Math.abs(closest.offset - median);
            for (Sample s : sourceSamples) {
                long diff = Math.abs(s.offset - median);
                if (diff < minDiff) { minDiff = diff; closest = s; }
            }
            trusted.add(closest);
        }

        // 精度×RTT 加权平均：高精度源权重高、低延迟源权重高
        double weightSum = 0;
        double weighted = 0;
        for (Sample s : trusted) {
            double w = s.precision / (s.rtt + 100.0);  // +100ms 兜底，避免除零并封顶权重
            weighted += s.offset * w;
            weightSum += w;
        }
        return (long) (weighted / weightSum);
    }

    // ==================== NTP (SNTP) ====================

    /**
     * 最小 SNTP 客户端：向 NTP 服务器发送 48 字节请求，解析返回时间戳。
     * offset = ((T2 - T1) + (T3 - T4)) / 2
     */
    private static Sample fetchNtp(String host) throws IOException {
        byte[] req = new byte[48];
        req[0] = 0x23; // LI=0, VN=4, Mode=3 (client)

        long t1 = System.currentTimeMillis();
        setNtpTimestamp(req, 40, t1); // 客户端发送时间写入 transmit 字段

        DatagramSocket socket = new DatagramSocket();
        socket.setSoTimeout(NTP_TIMEOUT_MS);
        try {
            InetAddress addr = InetAddress.getByName(host);
            DatagramPacket send = new DatagramPacket(req, req.length, addr, NTP_PORT);
            socket.send(send);

            byte[] resp = new byte[48];
            DatagramPacket recv = new DatagramPacket(resp, resp.length);
            socket.receive(recv);
            long t4 = System.currentTimeMillis();

            long t1Echo = ntpToMillis(readUint32(resp, 24), readUint32(resp, 28)); // originate
            long t2 = ntpToMillis(readUint32(resp, 32), readUint32(resp, 36));     // receive
            long t3 = ntpToMillis(readUint32(resp, 40), readUint32(resp, 44));     // transmit

            // 服务器时间戳与本地相差超过 1 天视为异常响应
            if (Math.abs(t3 - t4) > 24 * 3600 * 1000L) {
                throw new IOException("NTP 时间戳异常");
            }

            long offset;
            long rtt;
            if (Math.abs(t1Echo - t1) <= 2000) {
                // 完整四时间戳公式（服务器正确回填 originate）
                offset = ((t2 - t1) + (t3 - t4)) / 2;
                rtt = (t4 - t1) - (t3 - t2);
            } else {
                // 服务器未回填 originate（极简实现），退化为仅用 transmit 时间戳
                offset = t3 - (t1 + t4) / 2;
                rtt = t4 - t1;
            }
            if (rtt < 0) rtt = 0;

            return new Sample("NTP:" + host, offset, rtt, 1.0);
        } finally {
            socket.close();
        }
    }

    /** NTP 时间戳（秒+分数）→ Unix epoch ms。 */
    private static long ntpToMillis(long seconds, long fraction) {
        long unixSeconds = seconds - NTP_EPOCH_OFFSET;
        long ms = unixSeconds * 1000L;
        long fracMs = (fraction * 1000L) / 0x100000000L;
        return ms + fracMs;
    }

    /** Unix epoch ms → 写入 NTP 时间戳（秒+分数）。 */
    private static void setNtpTimestamp(byte[] buf, int off, long millis) {
        long seconds = millis / 1000L + NTP_EPOCH_OFFSET;
        long fraction = ((millis % 1000L) * 0x100000000L) / 1000L;
        writeUint32(buf, off, seconds);
        writeUint32(buf, off + 4, fraction);
    }

    private static long readUint32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24)
                | ((long) (b[off + 1] & 0xFF) << 16)
                | ((long) (b[off + 2] & 0xFF) << 8)
                | ((long) (b[off + 3] & 0xFF));
    }

    private static void writeUint32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    // ==================== HTTP 兜底源 ====================

    /**
     * 通过 HTTP Date 响应头获取服务器时间，计算 offset（秒级精度）。
     */
    private static Sample fetchDateHeader(String url) throws IOException {
        long t0 = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "text/html")
                .build();

        try (Response response = client.newCall(request).execute()) {
            long t1 = System.currentTimeMillis();
            String dateStr = response.header("Date");
            if (dateStr == null) throw new IOException("无Date头");

            SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
            Date date = sdf.parse(dateStr);
            if (date == null) throw new IOException("Date解析失败");

            long serverTime = date.getTime();
            long rtt = t1 - t0;
            long offset = serverTime - t0 - rtt / 2;
            return new Sample("HTTP-Date:" + url, offset, rtt, 0.2);
        } catch (ParseException e) {
            throw new IOException("Date解析异常: " + e.getMessage());
        }
    }

    /**
     * 通过 timeapi.io JSON API 获取时间（毫秒级，海外）。
     */
    private static Sample fetchTimeApiIo() throws IOException {
        long t0 = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url("https://timeapi.io/api/Time/current/zone?timeZone=Asia/Shanghai")
                .header("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            long t1 = System.currentTimeMillis();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            int year = extractInt(body, "year");
            int month = extractInt(body, "month");
            int day = extractInt(body, "day");
            int hour = extractInt(body, "hour");
            int minute = extractInt(body, "minute");
            int seconds = extractInt(body, "seconds", 0);
            int millis = extractInt(body, "milliSeconds", 0);

            if (year <= 0) throw new IOException("JSON解析失败");

            java.util.Calendar cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"));
            cal.set(year, month - 1, day, hour, minute, seconds);
            cal.set(java.util.Calendar.MILLISECOND, millis);
            long serverTime = cal.getTimeInMillis();

            long rtt = t1 - t0;
            long offset = serverTime - t0 - rtt / 2;
            return new Sample("timeapi.io", offset, rtt, 1.0);
        }
    }

    /**
     * 通过 api.shijian.online 获取毫秒级时间戳（国内部署，延迟低）。
     */
    private static Sample fetchShijianOnline() throws IOException {
        long t0 = System.currentTimeMillis();
        Request request = new Request.Builder()
                .url("https://api.shijian.online/timestamp/")
                .header("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            long t1 = System.currentTimeMillis();
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            long ts = extractLong(body, "timestamp", 0);
            if (ts <= 0) throw new IOException("timestamp 解析失败");

            long rtt = t1 - t0;
            long offset = ts - t0 - rtt / 2;
            return new Sample("shijian.online", offset, rtt, 1.0);
        }
    }

    // ==================== JSON 数值提取 ====================

    private static int extractInt(String json, String key) {
        return extractInt(json, key, 0);
    }

    private static int extractInt(String json, String key, int defaultVal) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultVal;

        int start = json.indexOf(":", idx + search.length());
        if (start < 0) return defaultVal;

        start++;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }

        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        if (end == start) return defaultVal;
        try {
            return Integer.parseInt(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static long extractLong(String json, String key, long defaultVal) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return defaultVal;

        int start = json.indexOf(":", idx + search.length());
        if (start < 0) return defaultVal;

        start++;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\t')) {
            start++;
        }

        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }

        if (end == start) return defaultVal;
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    // ==================== 内部数据类 ====================

    private static class Sample {
        final String source;
        final long offset;      // ms
        final long rtt;         // ms
        final double precision; // 1.0=毫秒级，0.2=秒级(HTTP Date 头)

        Sample(String source, long offset, long rtt, double precision) {
            this.source = source;
            this.offset = offset;
            this.rtt = rtt;
            this.precision = precision;
        }
    }
}
