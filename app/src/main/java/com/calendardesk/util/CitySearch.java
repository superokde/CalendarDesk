package com.calendardesk.util;

import android.content.Context;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地全国城市库（cities.json，3393 条省/市/区县）。
 * 提供打字联想（子串匹配），选中后用 adcode 去和风天气解析 Location ID。
 */
public class CitySearch {

    public static class CityEntry {
        public String n; // 名称，如"南山区"
        public String p; // 上级区划，如"广东省深圳市"
        public String a; // adcode，如"440305"
    }

    private static List<CityEntry> cities;

    private CitySearch() {}

    private static synchronized void ensureLoaded(Context ctx) {
        if (cities != null) return;
        List<CityEntry> list = new ArrayList<>();
        try {
            InputStream is = ctx.getApplicationContext().getAssets().open("cities.json");
            list = new Gson().fromJson(new InputStreamReader(is, "UTF-8"),
                    new TypeToken<List<CityEntry>>() {}.getType());
            is.close();
        } catch (Exception e) {
            list = new ArrayList<>();
        }
        cities = list;
    }

    /**
     * 按名称子串模糊匹配，返回前 limit 条（含上级区划用于消歧）。
     */
    public static List<CityEntry> search(Context ctx, String query, int limit) {
        ensureLoaded(ctx);
        List<CityEntry> result = new ArrayList<>();
        if (query == null || cities == null) return result;

        String q = query.trim();
        if (q.isEmpty()) return result;

        for (CityEntry e : cities) {
            if (result.size() >= limit) break;
            if (e.n != null && e.n.contains(q)) result.add(e);
        }
        return result;
    }

    /** 按 adcode 精确查找。 */
    public static CityEntry findByAdcode(Context ctx, String adcode) {
        ensureLoaded(ctx);
        if (cities == null || adcode == null) return null;
        for (CityEntry e : cities) {
            if (adcode.equals(e.a)) return e;
        }
        return null;
    }
}
