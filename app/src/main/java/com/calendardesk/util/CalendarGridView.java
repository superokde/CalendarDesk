package com.calendardesk.util;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.calendardesk.util.HolidayFetcher.HolidayInfo;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * 自定义 Canvas 日历网格视图。
 * 单 View 绘制 vs 42 个 TextView — 内存和渲染开销大幅降低。
 */
public class CalendarGridView extends View {

    // ---------- 颜色常量 ----------
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_LUNAR = 0xFFC0C0C0;
    private static final int COLOR_TODAY_TEXT = 0xFF4DA6FF;  // 今天用蓝色文字
    private static final int COLOR_BADGE_REST = 0xFF4CAF50;
    private static final int COLOR_BADGE_WORK = 0xFFF44336;

    // 轮播间隔
    private static final int CAROUSEL_INTERVAL_MS = 5000;

    // ---------- 画笔 ----------
    private final Paint dayNumPaint;
    private final Paint dayNumTodayPaint;
    private final Paint lunarPaint;
    private final Paint lunarTodayPaint;
    private final Paint badgeRestPaint;
    private final Paint badgeWorkPaint;

    // ---------- 数据 ----------
    private int year;
    private int month;
    private int todayDay;
    private int firstDayOfWeek;
    private int daysInMonth;

    private final String[] cellTexts = new String[32];
    private final List<String>[] carouselItems = new ArrayList[32];
    private final int[] carouselIndex = new int[32];
    private final boolean[] isRestDay = new boolean[32];
    private final boolean[] isWorkDay = new boolean[32];
    private final boolean[] isToday = new boolean[32];

    private OnDayClickListener onDayClickListener;

    // ---------- 轮播 ----------
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable carouselRunnable = new Runnable() {
        @Override
        public void run() {
            boolean changed = false;
            for (int d = 1; d <= daysInMonth; d++) {
                List<String> items = carouselItems[d];
                if (items != null && items.size() > 1) {
                    carouselIndex[d] = (carouselIndex[d] + 1) % items.size();
                    cellTexts[d] = items.get(carouselIndex[d]);
                    changed = true;
                }
            }
            if (changed) invalidate();
            handler.postDelayed(this, CAROUSEL_INTERVAL_MS);
        }
    };

    public CalendarGridView(Context context) {
        this(context, null);
    }

    public CalendarGridView(Context context, AttributeSet attrs) {
        super(context, attrs);

        dayNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dayNumPaint.setColor(COLOR_WHITE);
        dayNumPaint.setTextAlign(Paint.Align.CENTER);
        dayNumPaint.setTypeface(Typeface.DEFAULT_BOLD);

        dayNumTodayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dayNumTodayPaint.setColor(COLOR_TODAY_TEXT);  // 蓝色文字，不用圆形底
        dayNumTodayPaint.setTextAlign(Paint.Align.CENTER);
        dayNumTodayPaint.setTypeface(Typeface.DEFAULT_BOLD);

        lunarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lunarPaint.setColor(COLOR_LUNAR);
        lunarPaint.setTextAlign(Paint.Align.CENTER);

        lunarTodayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        lunarTodayPaint.setColor(0xFFF5F5E0);
        lunarTodayPaint.setTextAlign(Paint.Align.CENTER);

        badgeRestPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeRestPaint.setColor(COLOR_BADGE_REST);
        badgeRestPaint.setTextAlign(Paint.Align.CENTER);
        badgeRestPaint.setTypeface(Typeface.DEFAULT_BOLD);

        badgeWorkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        badgeWorkPaint.setColor(COLOR_BADGE_WORK);
        badgeWorkPaint.setTextAlign(Paint.Align.CENTER);
        badgeWorkPaint.setTypeface(Typeface.DEFAULT_BOLD);

        // API 21+ 支持负 letterSpacing，防止大号数字溢出格子
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            dayNumPaint.setLetterSpacing(-0.08f);
            dayNumTodayPaint.setLetterSpacing(-0.08f);
            lunarPaint.setLetterSpacing(-0.04f);
            lunarTodayPaint.setLetterSpacing(-0.04f);
        }

        for (int i = 0; i < 32; i++) {
            carouselItems[i] = new ArrayList<>();
        }
    }

    public void setData(int year, int month, int todayDay,
                        Map<String, HolidayInfo> holidayMap) {
        this.year = year;
        this.month = month;
        this.todayDay = todayDay;

        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.set(year, month - 1, 1, 0, 0, 0);
        cal.set(Calendar.MILLISECOND, 0);
        // 周一开头：Monday=0 … Sunday=6（Calendar.DAY_OF_WEEK 周日=1）
        firstDayOfWeek = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);

        for (int d = 1; d <= daysInMonth; d++) {
            carouselItems[d].clear();
            carouselIndex[d] = 0;
            isRestDay[d] = false;
            isWorkDay[d] = false;
            isToday[d] = (d == todayDay && todayDay > 0);
        }

        for (int d = 1; d <= daysInMonth; d++) {
            String lunarDay = LunarHelper.getDayInChinese(year, month, d);
            List<String> festivals = LunarHelper.getFestivals(year, month, d);

            String mmdd = String.format("%02d-%02d", month, d);
            HolidayInfo holiday = (holidayMap != null) ? holidayMap.get(mmdd) : null;
            if (holiday != null) {
                if (holiday.isHoliday) isRestDay[d] = true;
                if (holiday.isWorkExtra) isWorkDay[d] = true;
            }

            List<String> items = carouselItems[d];
            if (festivals != null && !festivals.isEmpty()) {
                for (String f : festivals) {
                    if (f != null && !f.isEmpty() && !"--".equals(f)) {
                        items.add(f);
                    }
                }
            }
            if (holiday != null && holiday.name != null
                    && !holiday.name.isEmpty() && !"班".equals(holiday.name)) {
                items.add(holiday.name);
            }
            if (items.isEmpty()) {
                if (lunarDay != null && !lunarDay.isEmpty()) {
                    items.add(lunarDay);
                } else {
                    items.add("--");
                }
            }

            cellTexts[d] = items.get(0);
        }

        invalidate();
    }

    public void advanceCarousel() {
        carouselRunnable.run();
    }

    public void setOnDayClickListener(OnDayClickListener listener) {
        this.onDayClickListener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(carouselRunnable, CAROUSEL_INTERVAL_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(carouselRunnable);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (daysInMonth == 0) return;

        int w = getWidth();
        int h = getHeight();

        float cellW = w / 7f;
        float cellH = h / 6f;

        // ---- 字体大小（day放大20% → 0.86，lunar 0.22，badge 0.24） ----
        float dayFontSize = cellW * 0.86f;
        float lunarFontSize = cellW * 0.22f;
        float badgeFontSize = cellW * 0.24f;

        dayNumPaint.setTextSize(dayFontSize);
        dayNumTodayPaint.setTextSize(dayFontSize);
        lunarPaint.setTextSize(lunarFontSize);
        lunarTodayPaint.setTextSize(lunarFontSize);
        badgeRestPaint.setTextSize(badgeFontSize);
        badgeWorkPaint.setTextSize(badgeFontSize);

        // 获取字体度量用于精确计算间距
        Paint.FontMetrics fmDay = dayNumPaint.getFontMetrics();
        float dayTextHeight = fmDay.descent - fmDay.ascent;
        Paint.FontMetrics fmLunar = lunarPaint.getFontMetrics();
        float lunarTextHeight = fmLunar.descent - fmLunar.ascent;

        for (int d = 1; d <= daysInMonth; d++) {
            int col = (firstDayOfWeek + d - 1) % 7;
            int row = (firstDayOfWeek + d - 1) / 7;

            float cx = col * cellW + cellW / 2f;
            float cy = row * cellH + cellH / 2f;

            // 日期数字 — 今天用蓝色文字（整体下移，兼顾首行避让与末行不被万年历遮挡）
            Paint numPaint = isToday[d] ? dayNumTodayPaint : dayNumPaint;
            // 数字与农历之间用负间距紧密贴合
            float dayY = cy - lunarTextHeight * 0.05f + dayTextHeight * 0.12f;
            canvas.drawText(String.valueOf(d), cx, dayY, numPaint);

            // 农历/节日文字 — 负间距叠在数字下方
            Paint lunarP = isToday[d] ? lunarTodayPaint : lunarPaint;
            // dayTextHeight * 0.40 = 数字和农历视觉上紧密相连
            float lunarY = dayY + dayTextHeight * 0.40f;
            String text = cellTexts[d];
            if (text != null) {
                canvas.drawText(text, cx, lunarY, lunarP);
            }

            // 休/班角标
            if (isRestDay[d]) {
                float badgeX = (col + 1) * cellW - cellW * 0.10f;
                float badgeY = row * cellH + cellH * 0.17f;
                canvas.drawText("休", badgeX, badgeY, badgeRestPaint);
            } else if (isWorkDay[d]) {
                float badgeX = (col + 1) * cellW - cellW * 0.10f;
                float badgeY = row * cellH + cellH * 0.17f;
                canvas.drawText("班", badgeX, badgeY, badgeWorkPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && onDayClickListener != null) {
            float x = event.getX();
            float y = event.getY();

            int col = (int) (x / (getWidth() / 7f));
            int row = (int) (y / (getHeight() / 6f));

            int day = col - firstDayOfWeek + 1 + row * 7;
            if (day >= 1 && day <= daysInMonth) {
                onDayClickListener.onDayClick(year, month, day);
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    public interface OnDayClickListener {
        void onDayClick(int year, int month, int day);
    }
}
