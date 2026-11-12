package com.calendardesk.util;

import com.nlf.calendar.Lunar;
import com.nlf.calendar.Solar;

import java.util.ArrayList;
import java.util.List;

/**
 * 农历/节气/传统节日转换工具。
 * 封装 cn.6tail:lunar 库，与 Web 版 lunar-javascript 同作者，API 高度一致。
 */
public class LunarHelper {

    private LunarHelper() {}

    /**
     * 获取农历日的中文表示，如"初一"、"廿一"。
     */
    public static String getDayInChinese(int year, int month, int day) {
        try {
            Solar solar = Solar.fromYmd(year, month, day);
            return solar.getLunar().getDayInChinese();
        } catch (Exception e) {
            return "--";
        }
    }

    /**
     * 获取某天的全部节日/节气列表（去重）。
     * 包含：节气、传统节日（lunar.getFestivals）、其他节日（lunar.getOtherFestivals）。
     */
    public static List<String> getFestivals(int year, int month, int day) {
        List<String> result = new ArrayList<>();
        try {
            Solar solar = Solar.fromYmd(year, month, day);
            Lunar lunar = solar.getLunar();

            String jieQi = lunar.getJieQi();
            if (jieQi != null && !jieQi.isEmpty()) {
                result.add(jieQi);
            }

            List<String> festivals = lunar.getFestivals();
            if (festivals != null) {
                for (String f : festivals) {
                    if (f != null && !f.isEmpty()) result.add(f);
                }
            }

            List<String> other = lunar.getOtherFestivals();
            if (other != null) {
                for (String f : other) {
                    if (f != null && !f.isEmpty()) result.add(f);
                }
            }
        } catch (Exception e) {
            // 计算失败时返回空列表，调用方回退到农历日期
        }
        return result;
    }

    /**
     * 获取某天的常见万年历（黄历）信息，用于日历底部展示。
     */
    public static AlmanacInfo getAlmanac(int year, int month, int day) {
        try {
            Solar solar = Solar.fromYmd(year, month, day);
            Lunar lunar = solar.getLunar();

            AlmanacInfo info = new AlmanacInfo();
            info.yearGanZhi = nz(lunar.getYearInGanZhi());   // 年干支
            info.zodiac = nz(lunar.getYearShengXiao());      // 生肖
            info.monthCn = nz(lunar.getMonthInChinese());    // 农历月
            info.dayCn = nz(lunar.getDayInChinese());        // 农历日
            info.chong = nz(lunar.getDayChongDesc());        // 冲
            info.sha = nz(lunar.getSha());                   // 煞
            info.yi = lunar.getDayYi();                      // 宜
            info.ji = lunar.getDayJi();                      // 忌
            return info;
        } catch (Exception e) {
            return null;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 万年历/黄历信息数据类。 */
    public static class AlmanacInfo {
        public String yearGanZhi = "";
        public String zodiac = "";
        public String monthCn = "";
        public String dayCn = "";
        public String chong = "";
        public String sha = "";
        public List<String> yi = new ArrayList<>();
        public List<String> ji = new ArrayList<>();
    }
}
