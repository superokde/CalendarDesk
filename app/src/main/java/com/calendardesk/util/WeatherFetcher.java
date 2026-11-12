package com.calendardesk.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 和风天气 WebAPI v7 封装。
 * 支持全国任意城市：先用行政区划代码（adcode）解析出 Location ID，再按 ID 取天气。
 */
public class WeatherFetcher {

    /** 默认 API Key 为空：首次使用需用户自行填写（主界面 🔑 或设置页）。 */
    public static final String DEFAULT_KEY = "";

    /** 12 个常用城市快捷项（名称 + 行政区划代码 adcode）。 */
    private static final String[][] PRESET_CITIES = {
            {"东莞", "441900"}, {"北京", "110000"}, {"上海", "310000"}, {"广州", "440100"},
            {"深圳", "440300"}, {"杭州", "330100"}, {"南京", "320100"}, {"成都", "510100"},
            {"重庆", "500000"}, {"武汉", "420100"}, {"西安", "610100"}, {"厦门", "350200"}
    };

    private final OkHttpClient client;

    public WeatherFetcher(OkHttpClient client) {
        this.client = client;
    }

    // ---------- 公开 API ----------

    /** 获取常用城市名列表（快捷项）。 */
    public static List<String> getCities() {
        List<String> names = new ArrayList<>();
        for (String[] p : PRESET_CITIES) names.add(p[0]);
        return names;
    }

    /** 常用城市名 → adcode（非快捷项返回 null）。 */
    public static String getPresetAdcode(String name) {
        for (String[] p : PRESET_CITIES) {
            if (p[0].equals(name)) return p[1];
        }
        return null;
    }

    /**
     * 用行政区划代码解析城市，得到 Location ID + 规范名。
     * @param adcode 6 位行政区划代码，如 440300（深圳）、440305（南山）
     */
    public CitySuggestion resolveByAdcode(String adcode, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("API Key 未设置");

        String url = "https://geoapi.qweather.com/v2/city/lookup?location="
                + adcode + "&key=" + apiKey;
        JsonObject geoJson = httpGetJson(url);
        if (!"200".equals(getString(geoJson, "code")))
            throw new IOException("城市查询失败: " + getString(geoJson, "code"));

        JsonArray locArray = geoJson.getAsJsonArray("location");
        if (locArray == null || locArray.size() == 0)
            throw new IOException("未找到城市: " + adcode);

        JsonObject loc = locArray.get(0).getAsJsonObject();
        CitySuggestion s = new CitySuggestion();
        s.name = getString(loc, "name");
        s.locationId = getString(loc, "id");
        s.adm1 = getString(loc, "adm1");
        s.adm2 = getString(loc, "adm2");
        return s;
    }

    /**
     * 按 Location ID 获取实时天气 + 3 日预报。
     */
    public WeatherData fetchByLocationId(String locationId, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) throw new IOException("API Key 未设置");
        if (locationId == null || locationId.isEmpty()) throw new IOException("城市 ID 未设置");

        // 1. 实时天气
        String nowUrl = "https://devapi.qweather.com/v7/weather/now?location="
                + locationId + "&key=" + apiKey;
        JsonObject nowJson = httpGetJson(nowUrl);
        if (!"200".equals(getString(nowJson, "code")))
            throw new IOException("天气查询失败: " + getString(nowJson, "code"));

        JsonObject now = nowJson.getAsJsonObject("now");
        WeatherData data = new WeatherData();
        data.temp = getString(now, "temp");
        data.feelsLike = getString(now, "feelsLike");
        data.humidity = getString(now, "humidity");
        data.windSpeed = getString(now, "windSpeed");
        data.text = getString(now, "text");
        data.icon = getString(now, "icon");

        // 2. 3 日预报
        String threeUrl = "https://devapi.qweather.com/v7/weather/3d?location="
                + locationId + "&key=" + apiKey;
        JsonObject threeJson = httpGetJson(threeUrl);
        if ("200".equals(getString(threeJson, "code"))) {
            JsonArray dailyArray = threeJson.getAsJsonArray("daily");
            if (dailyArray != null) {
                for (int i = 0; i < dailyArray.size() && i < 3; i++) {
                    JsonObject day = dailyArray.get(i).getAsJsonObject();
                    ForecastDay fd = new ForecastDay();
                    fd.textDay = getString(day, "textDay");
                    fd.tempMin = getString(day, "tempMin");
                    fd.tempMax = getString(day, "tempMax");
                    data.forecast.add(fd);
                }
            }
        }

        return data;
    }

    // ---------- 内部 ----------

    private JsonObject httpGetJson(String url) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null)
                throw new IOException("HTTP " + response.code());
            return JsonParser.parseString(response.body().string()).getAsJsonObject();
        }
    }

    private static String getString(JsonObject obj, String key) {
        return (obj != null && obj.has(key) && !obj.get(key).isJsonNull())
                ? obj.get(key).getAsString() : "";
    }

    // ---------- 数据类 ----------

    /** 城市解析结果。 */
    public static class CitySuggestion {
        public String name = "";        // 规范名，如"南山"
        public String locationId = "";  // Location ID
        public String adm1 = "";        // 省
        public String adm2 = "";        // 市
    }

    public static class WeatherData {
        public String temp = "--";
        public String feelsLike = "--";
        public String humidity = "--";
        public String windSpeed = "--";
        public String text = "加载中…";
        public String icon = "100";  // 和风天气图标代码
        public List<ForecastDay> forecast = new ArrayList<>();
    }

    public static class ForecastDay {
        public String textDay = "未知";
        public String tempMin = "--";
        public String tempMax = "--";
    }
}
