package com.calendardesk.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 法定节假日数据获取。
 * 原 timor.tech 已被 Cloudflare 人机验证拦截，改从 holiday-cn（NateScarlet/holiday-cn）
 * 拉取每年 JSON，双 CDN 容错：jsdelivr（国内快） + raw.githubusercontent（备用）。
 *
 * 数据格式：{ "year":2026, "days":[ {name, date:"YYYY-MM-DD", isOffDay:bool} ] }
 * isOffDay=true  → 休息日（休）
 * isOffDay=false → 调休补班（班）
 */
public class HolidayFetcher {

    private static final String[] URL_TEMPLATES = {
            "https://cdn.jsdelivr.net/gh/NateScarlet/holiday-cn@master/%d.json",
            "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/%d.json"
    };

    private final OkHttpClient client;
    private final Map<Integer, Map<String, HolidayInfo>> cache = new HashMap<>();

    public HolidayFetcher(OkHttpClient client) {
        this.client = client;
    }

    /**
     * 获取指定年份的节假日数据。
     *
     * @return Map&lt;"MM-dd", HolidayInfo&gt;，例如 {"05-01": {name:"劳动节", isHoliday:true}}
     */
    public Map<String, HolidayInfo> fetch(int year) throws IOException {
        Map<String, HolidayInfo> cached = cache.get(year);
        if (cached != null) return cached;

        IOException lastErr = null;
        for (String tpl : URL_TEMPLATES) {
            try {
                Map<String, HolidayInfo> map = fetchFrom(tpl, year);
                cache.put(year, map);
                return map;
            } catch (IOException e) {
                lastErr = e;
            }
        }
        throw lastErr != null ? lastErr : new IOException("节假日数据源不可用");
    }

    private Map<String, HolidayInfo> fetchFrom(String tpl, int year) throws IOException {
        Map<String, HolidayInfo> map = new HashMap<>();

        String url = String.format(tpl, year);
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build();

        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }

            String body = response.body().string();
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray days = root.getAsJsonArray("days");
            if (days == null || days.size() == 0) {
                throw new IOException("无 days 字段");
            }

            for (int i = 0; i < days.size(); i++) {
                JsonObject d = days.get(i).getAsJsonObject();
                String date = getString(d, "date");   // "2026-01-01"
                String name = getString(d, "name");
                boolean isOffDay = d.has("isOffDay") && d.get("isOffDay").getAsBoolean();

                if (date == null || date.length() < 10) continue;
                String mmdd = date.substring(5);      // "01-01"

                if (isOffDay) {
                    // 休息日：角标"休"，单元格轮播显示节日名
                    map.put(mmdd, new HolidayInfo(name, true, false));
                } else {
                    // 调休补班：角标"班"，单元格不显示节日名（与旧版一致）
                    map.put(mmdd, new HolidayInfo("班", false, true));
                }
            }
        }

        if (map.isEmpty()) throw new IOException("节假日数据为空");
        return map;
    }

    private static String getString(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsString() : "";
    }

    /**
     * 清除缓存（跨年时调用）。
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 法定假日/调休信息。
     */
    public static class HolidayInfo {
        public final String name;
        public final boolean isHoliday;   // 休息日
        public final boolean isWorkExtra; // 调休补班日

        public HolidayInfo(String name, boolean isHoliday, boolean isWorkExtra) {
            this.name = name;
            this.isHoliday = isHoliday;
            this.isWorkExtra = isWorkExtra;
        }
    }
}
