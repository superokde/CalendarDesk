package com.calendardesk.fragment;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.calendardesk.MainActivity;
import com.calendardesk.R;
import com.calendardesk.util.CalendarGridView;
import com.calendardesk.util.CitySearch;
import com.calendardesk.util.HolidayFetcher;
import com.calendardesk.util.HolidayFetcher.HolidayInfo;
import com.calendardesk.util.TimeSyncHelper;
import com.calendardesk.util.LunarHelper;
import com.calendardesk.util.LunarHelper.AlmanacInfo;
import com.calendardesk.util.PrefsHelper;
import com.calendardesk.util.WeatherFetcher;
import com.calendardesk.util.WeatherFetcher.WeatherData;
import com.calendardesk.util.WeatherFetcher.ForecastDay;

import java.io.IOException;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import okhttp3.OkHttpClient;

/**
 * 主界面 Fragment：时钟 + 天气 + 日历。
 * 所有 UI 更新通过 Handler 在主线程调度，不使用 Timer/WorkManager。
 */
public class MainFragment extends Fragment {

    // ---------- 天气图标映射（与 Web 版一致，Unicode 字符） ----------
    private static final java.util.Map<String, String> WEATHER_ICONS = new java.util.HashMap<>();
    static {
        WEATHER_ICONS.put("晴", "☀");
        WEATHER_ICONS.put("少云", "🌤");
        WEATHER_ICONS.put("晴间多云", "⛅");
        WEATHER_ICONS.put("多云", "☁");
        WEATHER_ICONS.put("阴", "▧");
        WEATHER_ICONS.put("小雨", "☂");
        WEATHER_ICONS.put("中雨", "☔");
        WEATHER_ICONS.put("大雨", "☔");
        WEATHER_ICONS.put("雷阵雨", "⚡");
        WEATHER_ICONS.put("小雪", "❄");
        WEATHER_ICONS.put("中雪", "❄");
        WEATHER_ICONS.put("大雪", "❄");
        WEATHER_ICONS.put("雾", "▒");
        WEATHER_ICONS.put("霾", "▒");
    }

    // ---------- Handler ----------
    private final Handler handler = new Handler(Looper.getMainLooper());

    // ---------- 网络 ----------
    private OkHttpClient httpClient;
    private WeatherFetcher weatherFetcher;
    private HolidayFetcher holidayFetcher;

    // ---------- UI 组件 ----------
    private TextView dateInfo;
    private TextView timeHH;
    private TextView timeColon;
    private TextView timeMM;
    private TextView timeSS;
    private TextView citySelector;
    private LinearLayout cityDropdown;
    private TextView tempDisplay;
    private TextView weatherIcon;
    private TextView weatherDesc;
    private TextView weatherDetails;
    private LinearLayout forecastRow;
    private CalendarGridView calendarGrid;
    private TextView calendarHeader;
    private TextView btnFullscreen;
    private LinearLayout caliBar;
    private TextView btnCalSync;
    private TextView btnApiKeyBar;    // 校准栏上的 🔑
    private TextView btnCalPrev;      // 上一月
    private TextView btnCalNext;      // 下一月
    private TextView almanacView;     // 万年历/黄历条

    // ---------- 字体 ----------
    private Typeface digital7Mono;

    // ---------- 状态 ----------
    private String currentCity = "东莞";
    private String currentCityId = "";   // 和风天气 Location ID
    private android.app.AlertDialog cityDialog;
    private long manualOffset = 0;
    private int lastDisplayDate = -1;        // 用于检测跨日
    private int lastWeatherHour = -1;         // 每2小时刷新一次天气
    private boolean weatherLoading = false;
    private long lastWeatherRefreshMs = 0;    // 上次刷新时间戳，防止频繁调用

    // 日历浏览（月份）状态
    private int viewYear = 0;                // 当前日历显示的年份
    private int viewMonth = 0;               // 当前日历显示的月份 (1-12)
    private boolean monthBrowsing = false;   // 是否正在浏览非本月

    // 天气自动重试
    private int weatherRetryAttempt = 0;

    // 时间同步周期重试
    private long lastAutoSyncMs = 0;

    // ---------- 常量 ----------
    private static final long AUTO_RETURN_MONTH_MS = 30_000L;      // 30秒无操作恢复本月
    private static final long WEATHER_RETRY_BASE_MS = 15_000L;     // 天气失败重试基础间隔
    private static final long WEATHER_RETRY_MAX_MS = 10 * 60_000L; // 天气重试最大间隔
    private static final long TIME_SYNC_INTERVAL_MS = 60_000L;     // 周期时间同步

    // ---------- 轮播 ----------
    private final List<Runnable> carouselTasks = new java.util.ArrayList<>();

    // ---------- 浮动按钮自动隐藏 ----------
    private final Runnable hideFloatingBtnTask = new Runnable() {
        @Override
        public void run() {
            if (btnFullscreen != null) btnFullscreen.animate().alpha(0f).setDuration(800).start();
            if (caliBar != null) caliBar.animate().alpha(0f).setDuration(800).start();
            if (btnCalPrev != null) btnCalPrev.animate().alpha(0f).setDuration(800).start();
            if (btnCalNext != null) btnCalNext.animate().alpha(0f).setDuration(800).start();
        }
    };

    // ---------- 时钟刷新 ----------
    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 200);
        }
    };

    // ---------- 月份浏览自动恢复本月 ----------
    private final Runnable autoReturnRunnable = new Runnable() {
        @Override
        public void run() {
            if (monthBrowsing) {
                monthBrowsing = false;
                renderCurrentMonth();
            }
        }
    };

    // ---------- 天气失败自动重试 ----------
    private final Runnable weatherRetryRunnable = new Runnable() {
        @Override
        public void run() {
            refreshWeather();
        }
    };

    // ---------- 生命周期 ----------

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 初始化网络
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        weatherFetcher = new WeatherFetcher(httpClient);
        holidayFetcher = new HolidayFetcher(httpClient);

        // 加载 Digital-7 Mono 字体
        try {
            digital7Mono = Typeface.createFromAsset(
                    requireContext().getAssets(), "fonts/digital7_mono.ttf");
        } catch (Exception e) {
            digital7Mono = Typeface.MONOSPACE; // 回退到系统等宽字体
        }

        // 恢复持久化状态
        manualOffset = PrefsHelper.getManualOffset();
        currentCity = PrefsHelper.getCurrentCity("东莞");
        currentCityId = PrefsHelper.getCurrentCityId("");

        // 绑定 UI
        bindViews(view);

        // 初始渲染当前月 + 当天万年历（在 bindViews 之后）
        renderCurrentMonth();
        renderAlmanac();
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(clockTick);
        showFloatingButtons();

        // 从设置页返回时，同步可能变化的城市
        String savedCity = PrefsHelper.getCurrentCity("东莞");
        if (!savedCity.equals(currentCity)) {
            currentCity = savedCity;
            currentCityId = PrefsHelper.getCurrentCityId("");
            if (citySelector != null) citySelector.setText(currentCity + " ▼");
            refreshWeather(true);
        }

        // 从设置页返回，仅当距上次刷新超过2小时才更新天气
        if (System.currentTimeMillis() - lastWeatherRefreshMs > 2 * 3600_000L) {
            refreshWeather();
        }

        // 触发时间同步（首次后台异步执行）
        TimeSyncHelper.syncAsync();
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(hideFloatingBtnTask);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        dismissCityDialog();
        handler.removeCallbacksAndMessages(null);
        for (Runnable task : carouselTasks) {
            handler.removeCallbacks(task);
        }
        carouselTasks.clear();
    }

    /** 供 MainActivity 调用的触摸回调 */
    public void onUserInteraction() {
        showFloatingButtons();
    }

    // ---------- 视图绑定 ----------

    private void bindViews(View view) {
        dateInfo = view.findViewById(R.id.date_info);
        timeHH = view.findViewById(R.id.time_hh);
        timeColon = view.findViewById(R.id.time_colon);
        timeMM = view.findViewById(R.id.time_mm);
        timeSS = view.findViewById(R.id.time_ss);
        citySelector = view.findViewById(R.id.city_selector);
        cityDropdown = view.findViewById(R.id.city_dropdown);
        tempDisplay = view.findViewById(R.id.temp_display);
        weatherIcon = view.findViewById(R.id.weather_icon);
        weatherDesc = view.findViewById(R.id.weather_desc);
        weatherDetails = view.findViewById(R.id.weather_details);
        forecastRow = view.findViewById(R.id.forecast_row);
        calendarGrid = view.findViewById(R.id.calendar_grid);
        calendarHeader = view.findViewById(R.id.calendar_header);
        btnFullscreen = view.findViewById(R.id.btn_fullscreen);
        caliBar = view.findViewById(R.id.calibration_bar);
        btnCalSync = view.findViewById(R.id.btn_cal_sync);
        btnApiKeyBar = view.findViewById(R.id.btn_api_key_bar);
        btnCalPrev = view.findViewById(R.id.btn_cal_prev);
        btnCalNext = view.findViewById(R.id.btn_cal_next);
        almanacView = view.findViewById(R.id.almanac_view);

        // 设置时钟字体
        timeHH.setTypeface(digital7Mono);
        timeColon.setTypeface(digital7Mono);
        timeMM.setTypeface(digital7Mono);
        timeSS.setTypeface(digital7Mono);

        // 全屏按钮
        btnFullscreen.setOnClickListener(v -> toggleFullscreen());

        // 重校按钮
        btnCalSync.setOnClickListener(v -> resyncTime());

        // API Key 按钮 → 弹出编辑框
        View.OnClickListener apiKeyListener = v -> showApiKeyDialog();
        if (btnApiKeyBar != null) btnApiKeyBar.setOnClickListener(apiKeyListener);

        // 日历月份浏览按钮
        if (btnCalPrev != null) btnCalPrev.setOnClickListener(v -> browseMonth(-1));
        if (btnCalNext != null) btnCalNext.setOnClickListener(v -> browseMonth(+1));

        // 城市选择 → 全国城市搜索
        citySelector.setOnClickListener(v -> showCitySearchDialog());
        citySelector.setText(currentCity + " ▼");
    }

    // ---------- 时钟更新 ----------

    private void updateClock() {
        if (dateInfo == null || timeHH == null || timeMM == null || timeSS == null) return;

        long beijingMs = TimeSyncHelper.getBeijingTimeMs(manualOffset);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));
        cal.setTimeInMillis(beijingMs);

        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH) + 1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        int second = cal.get(Calendar.SECOND);
        int weekday = cal.get(Calendar.DAY_OF_WEEK) - 1;

        String[] weekNames = {"日", "一", "二", "三", "四", "五", "六"};
        dateInfo.setText(month + "月" + day + "日 星期" + weekNames[weekday]);

        timeHH.setText(String.format("%d", hour));
        timeMM.setText(String.format("%02d", minute));
        timeSS.setText(String.format("%02d", second));

        // 检测跨日
        int todayKey = year * 10000 + month * 100 + day;
        if (lastDisplayDate != todayKey) {
            lastDisplayDate = todayKey;
            renderAlmanac();                 // 跨日更新万年历
            if (!monthBrowsing) {
                renderCurrentMonth();
            }
        }

        // 每2小时整点刷新天气 (0时、2时、4时...)
        if (minute == 0 && second < 2 && hour % 2 == 0 && lastWeatherHour != hour) {
            lastWeatherHour = hour;
            refreshWeather();
        }

        // 周期时间同步（消除长期漂移导致的时差）
        long now = System.currentTimeMillis();
        if (now - lastAutoSyncMs > TIME_SYNC_INTERVAL_MS) {
            lastAutoSyncMs = now;
            TimeSyncHelper.syncAsync();
        }

        // 更新日历月份标题（浏览其他月份时不要被当前月覆盖）
        if (!monthBrowsing) {
            String headerText = year + "年" + month + "月";
            if (calendarHeader != null && !headerText.equals(calendarHeader.getText().toString())) {
                calendarHeader.setText(headerText);
            }
        }
    }

    // ---------- 日历渲染 ----------

    /** 取当前北京时间 [年, 月, 日]。 */
    private int[] getCurrentYmd() {
        long beijingMs = TimeSyncHelper.getBeijingTimeMs(manualOffset);
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+8"));
        cal.setTimeInMillis(beijingMs);
        return new int[]{
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH) + 1,
                cal.get(Calendar.DAY_OF_MONTH)
        };
    }

    /** 渲染当前月（含"今天"高亮）。 */
    private void renderCurrentMonth() {
        int[] c = getCurrentYmd();
        viewYear = c[0];
        viewMonth = c[1];
        monthBrowsing = false;
        renderCalendarAt(c[0], c[1], c[2]);
    }

    /** 浏览月份：delta = -1 上一月 / +1 下一月。 */
    private void browseMonth(int delta) {
        if (viewYear == 0 || viewMonth == 0) {
            int[] c = getCurrentYmd();
            viewYear = c[0];
            viewMonth = c[1];
        }

        int y = viewYear;
        int m = viewMonth + delta;
        if (m < 1) { m = 12; y--; }
        if (m > 12) { m = 1; y++; }

        viewYear = y;
        viewMonth = m;

        int[] c = getCurrentYmd();
        boolean isCur = (y == c[0] && m == c[1]);
        monthBrowsing = !isCur;
        int today = isCur ? c[2] : 0;

        renderCalendarAt(y, m, today);
        scheduleAutoReturn();
        showFloatingButtons();
    }

    /** 30秒无操作后自动恢复本月。 */
    private void scheduleAutoReturn() {
        handler.removeCallbacks(autoReturnRunnable);
        handler.postDelayed(autoReturnRunnable, AUTO_RETURN_MONTH_MS);
    }

    /** 渲染指定年月（today<=0 时不标记今天）。 */
    private void renderCalendarAt(final int year, final int month, final int today) {
        new Thread(() -> {
            // 获取显示年份的节假日（失败则本页不含"休/班"标记，但日历仍正常显示）
            Map<String, HolidayInfo> holidays = null;
            try {
                holidays = holidayFetcher.fetch(year);
            } catch (IOException ignored) {}

            // 预热下一年数据（纯 best-effort：404 等失败绝不能影响当年显示）
            try {
                holidayFetcher.fetch(year + 1);
            } catch (IOException ignored) {}

            final Map<String, HolidayInfo> finalHolidays = holidays;
            handler.post(() -> {
                if (calendarGrid != null) {
                    calendarGrid.setData(year, month, today, finalHolidays);
                }
                if (calendarHeader != null) {
                    calendarHeader.setText(year + "年" + month + "月");
                }
            });
        }).start();
    }

    // ---------- 万年历 / 黄历 ----------

    /** 渲染当天常见万年历内容（农历干支、生肖、冲煞、宜忌）。 */
    private void renderAlmanac() {
        if (almanacView == null) return;

        int[] c = getCurrentYmd();
        AlmanacInfo info = LunarHelper.getAlmanac(c[0], c[1], c[2]);
        if (info == null) {
            almanacView.setText("");
            return;
        }

        SpannableStringBuilder sb = new SpannableStringBuilder();

        // 第一行：农历 + 干支 + 生肖 + 冲煞（暖金色）
        appendColored(sb, "农历" + info.yearGanZhi + "年【" + info.zodiac + "】"
                + info.monthCn + "月" + info.dayCn, 0xFFE8C97A);
        appendColored(sb, "  ·  " + info.chong + " 煞" + info.sha, 0xFF9AA0A6);

        // 第二行：宜（绿色）
        appendColored(sb, "\n宜：", 0xFF7CCB7C);
        appendColored(sb, joinLimited(info.yi, 6), 0xFF7CCB7C);

        // 第三行：忌（红色）
        appendColored(sb, "\n忌：", 0xFFE08A8A);
        appendColored(sb, joinLimited(info.ji, 6), 0xFFE08A8A);

        almanacView.setText(sb);
    }

    private void appendColored(SpannableStringBuilder sb, String text, int color) {
        int start = sb.length();
        sb.append(text);
        sb.setSpan(new ForegroundColorSpan(color), start, sb.length(),
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private String joinLimited(List<String> list, int max) {
        if (list == null || list.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(list.size(), max);
        for (int i = 0; i < n; i++) {
            String s = list.get(i);
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" ");
            sb.append(s);
        }
        return sb.length() > 0 ? sb.toString() : "无";
    }

    // ---------- 天气 ----------

    private void refreshWeather() {
        refreshWeather(false);
    }

    /**
     * 刷新天气。
     * @param userInitiated true=用户主动触发（切城市/存Key），立即取消重试队列；false=自动触发。
     */
    private void refreshWeather(boolean userInitiated) {
        if (userInitiated) {
            handler.removeCallbacks(weatherRetryRunnable);
            weatherRetryAttempt = 0;
        }
        if (weatherLoading) return;
        weatherLoading = true;
        lastWeatherRefreshMs = System.currentTimeMillis();

        final String apiKey = PrefsHelper.getHefengKey(WeatherFetcher.DEFAULT_KEY);

        new Thread(() -> {
            try {
                String id = currentCityId;
                if (id == null || id.isEmpty()) {
                    // 首次/旧数据没有 Location ID 时，先解析再取天气
                    id = resolveCityId(currentCity, apiKey);
                    final String resolved = id;
                    handler.post(() -> {
                        currentCityId = resolved;
                        PrefsHelper.setCurrentCityId(resolved);
                    });
                }
                WeatherData data = weatherFetcher.fetchByLocationId(id, apiKey);
                handler.post(() -> {
                    weatherRetryAttempt = 0;
                    applyWeatherData(data);
                });
            } catch (IOException e) {
                final String msg = e.getMessage();
                handler.post(() -> onWeatherError(msg));
            } finally {
                weatherLoading = false;
            }
        }).start();
    }

    /** 天气失败：展示错误 + 安排自动重试（无 Key/Key 无效不重试）。 */
    private void onWeatherError(String msg) {
        showWeatherError(msg);
        scheduleWeatherRetry(msg);
    }

    private void scheduleWeatherRetry(String msg) {
        // 无 Key / Key 无效（HTTP 4xx）不密集重试，等用户改 Key 后手动刷新
        if (msg != null) {
            String lower = msg.toLowerCase(java.util.Locale.US);
            if (lower.contains("key") || lower.contains("401")
                    || lower.contains("402") || lower.contains("403")) {
                return;
            }
        }

        long shift = Math.min(weatherRetryAttempt, 6);
        long delay = Math.min(WEATHER_RETRY_BASE_MS * (1L << shift), WEATHER_RETRY_MAX_MS);
        weatherRetryAttempt++;
        handler.removeCallbacks(weatherRetryRunnable);
        handler.postDelayed(weatherRetryRunnable, delay);
    }

    private void applyWeatherData(WeatherData data) {
        if (tempDisplay != null) tempDisplay.setText(data.temp + "°");
        if (weatherDesc != null) weatherDesc.setText(data.text);
        if (weatherIcon != null)
            weatherIcon.setText(getWeatherEmoji(data.text));
        if (weatherDetails != null)
            weatherDetails.setText("体感 " + data.feelsLike + "° | 湿度 "
                    + data.humidity + "% | 风速 " + data.windSpeed + " km/h");
        renderForecast(data.forecast);
    }

    private void showWeatherError(String msg) {
        if (weatherDesc != null) weatherDesc.setText("天气获取失败");
        if (tempDisplay != null) tempDisplay.setText("--°");

        String hint;
        if (msg == null) {
            hint = "网络异常";
        } else if (msg.contains("Key 未设置") || msg.contains("Key 为空")) {
            hint = "请先设置和风天气 API Key（点左上角🔑）";
        } else if (msg.contains("Key")) {
            hint = "请检查 API Key 设置";
        } else {
            hint = "网络异常，稍后自动重试";
        }

        if (weatherDetails != null) {
            weatherDetails.setText("体感 --° | 湿度 --% | 风速 -- km/h | " + hint);
        }
    }

    private void renderForecast(List<ForecastDay> forecast) {
        if (forecastRow == null) return;
        forecastRow.removeAllViews();

        String[] dayLabels = {"今天", "明天", "后天"};

        // 动态创建预报 item
        for (int i = 0; i < forecast.size() && i < 3; i++) {
            ForecastDay fd = forecast.get(i);

            LinearLayout item = new LinearLayout(getContext());
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            item.setLayoutParams(params);

            TextView dayLabel = new TextView(getContext());
            dayLabel.setText(dayLabels[i]);
            dayLabel.setTextColor(0xFF888888);
            dayLabel.setTextSize(26);
            dayLabel.setGravity(android.view.Gravity.CENTER);

            TextView icon = new TextView(getContext());
            icon.setText(getWeatherEmoji(fd.textDay));
            icon.setTextSize(64);
            icon.setGravity(android.view.Gravity.CENTER);
            icon.setPadding(0, 4, 0, 4);

            TextView desc = new TextView(getContext());
            desc.setText(fd.textDay != null ? fd.textDay : "未知");
            desc.setTextColor(0xFFDDDDDD);
            desc.setTextSize(22);
            desc.setGravity(android.view.Gravity.CENTER);

            TextView temp = new TextView(getContext());
            temp.setText(fd.tempMin + "~" + fd.tempMax + "°");
            temp.setTextColor(0xFFAAAAAA);
            temp.setTextSize(21);
            temp.setGravity(android.view.Gravity.CENTER);

            item.addView(dayLabel);
            item.addView(icon);
            item.addView(desc);
            item.addView(temp);
            forecastRow.addView(item);
        }
    }

    // ---------- API Key 弹窗 ----------

    private void showApiKeyDialog() {
        if (getContext() == null) return;

        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(getContext());
        builder.setTitle("和风天气 API Key");

        final android.widget.EditText input = new android.widget.EditText(getContext());
        input.setText(PrefsHelper.getHefengKey(WeatherFetcher.DEFAULT_KEY));
        input.setHint("输入32位API Key");
        input.setSingleLine();
        input.setTextColor(0xFFFFFFFF);
        input.setBackgroundColor(0xFF222222);
        input.setPadding(24, 16, 24, 16);
        builder.setView(input);

        builder.setPositiveButton("保存", (dialog, which) -> {
            String key = input.getText().toString().trim();
            if (key.length() == 32) {
                PrefsHelper.setHefengKey(key);
                android.widget.Toast.makeText(getContext(),
                        "Key 已保存，正在刷新天气…", android.widget.Toast.LENGTH_SHORT).show();
                refreshWeather(true);
            } else {
                android.widget.Toast.makeText(getContext(),
                        "Key 格式不正确（应为32位字符串）", android.widget.Toast.LENGTH_LONG).show();
            }
        });

        builder.setNeutralButton("清空", (dialog, which) -> {
            PrefsHelper.setHefengKey("");
            android.widget.Toast.makeText(getContext(),
                    "已清空 Key", android.widget.Toast.LENGTH_SHORT).show();
            refreshWeather(true);
        });

        builder.setNegativeButton("取消", null);
        builder.show();
    }

    /** 天气文字 → Emoji 图标（与 Web 版映射一致） */
    private String getWeatherEmoji(String text) {
        if (text == null) return "☁";
        // 支持模糊匹配
        for (Map.Entry<String, String> e : WEATHER_ICONS.entrySet()) {
            if (text.contains(e.getKey())) return e.getValue();
        }
        return "☁";
    }

    // ---------- 全国城市搜索 ----------

    /** 城市名 → Location ID（优先快捷 adcode，其次本地库搜索，再和风解析）。 */
    private String resolveCityId(String name, String apiKey) throws IOException {
        String adcode = WeatherFetcher.getPresetAdcode(name);
        if ((adcode == null || adcode.isEmpty()) && getContext() != null) {
            List<CitySearch.CityEntry> hits = CitySearch.search(getContext(), name, 1);
            if (!hits.isEmpty()) adcode = hits.get(0).a;
        }
        if (adcode == null || adcode.isEmpty()) throw new IOException("未知城市: " + name);
        WeatherFetcher.CitySuggestion s = weatherFetcher.resolveByAdcode(adcode, apiKey);
        return s.locationId;
    }

    /** 打开全国城市选择对话框（输入 + 联想 + 快捷城市 + 设置入口）。 */
    private void showCitySearchDialog() {
        final android.content.Context ctx = getContext();
        if (ctx == null) return;

        final LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(8, 8, 8, 8);

        final EditText input = new EditText(ctx);
        input.setHint("输入城市名，如：深圳 / 南山 / 东莞");
        input.setSingleLine(true);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF888888);
        input.setTextSize(18);
        input.setBackgroundColor(0xFF222222);
        input.setPadding(24, 16, 24, 16);
        container.addView(input);

        final LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        container.addView(list);

        // 打字联想（防抖 200ms）
        final Runnable[] pending = new Runnable[1];
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                final String q = s.toString();
                if (pending[0] != null) handler.removeCallbacks(pending[0]);
                pending[0] = () -> updateCitySuggestions(list, q);
                handler.postDelayed(pending[0], 200);
            }
        });

        // 设置入口
        TextView settingsBtn = new TextView(ctx);
        settingsBtn.setText("⚙ 设置");
        settingsBtn.setTextColor(0xFF4DA6FF);
        settingsBtn.setTextSize(16);
        settingsBtn.setPadding(20, 12, 20, 8);
        settingsBtn.setClickable(true);
        settingsBtn.setFocusable(true);
        container.addView(settingsBtn);

        cityDialog = new android.app.AlertDialog.Builder(ctx)
                .setTitle("选择城市（全国）")
                .setView(container)
                .setNegativeButton("取消", null)
                .create();

        settingsBtn.setOnClickListener(v -> {
            dismissCityDialog();
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).openSettings();
            }
        });

        updateCitySuggestions(list, "");
        cityDialog.show();
    }

    private void dismissCityDialog() {
        if (cityDialog != null) {
            cityDialog.dismiss();
            cityDialog = null;
        }
    }

    /** 更新联想/快捷列表；query 为空时展示常用城市。 */
    private void updateCitySuggestions(LinearLayout list, String query) {
        final android.content.Context ctx = getContext();
        if (ctx == null || list == null) return;
        list.removeAllViews();

        if (query == null || query.trim().isEmpty()) {
            TextView tip = new TextView(ctx);
            tip.setText("常用城市");
            tip.setTextColor(0xFF888888);
            tip.setTextSize(14);
            tip.setPadding(12, 10, 12, 6);
            list.addView(tip);

            LinearLayout chips = new LinearLayout(ctx);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(12, 0, 12, 8);
            for (String name : WeatherFetcher.getCities()) {
                TextView chip = new TextView(ctx);
                chip.setText(name);
                chip.setTextColor(0xFFFFFFFF);
                chip.setTextSize(16);
                chip.setPadding(16, 10, 16, 10);
                chip.setBackgroundResource(R.drawable.cali_btn_bg);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMarginEnd(12);
                chip.setLayoutParams(lp);
                chip.setClickable(true);
                chip.setFocusable(true);
                chip.setOnClickListener(v -> selectCity(name, WeatherFetcher.getPresetAdcode(name)));
                chips.addView(chip);
            }
            list.addView(chips);
            return;
        }

        List<CitySearch.CityEntry> results = CitySearch.search(ctx, query, 30);
        if (results.isEmpty()) {
            TextView empty = new TextView(ctx);
            empty.setText("未找到匹配城市，请尝试完整名称");
            empty.setTextColor(0xFF888888);
            empty.setTextSize(15);
            empty.setPadding(16, 16, 16, 16);
            list.addView(empty);
            return;
        }

        for (CitySearch.CityEntry e : results) {
            TextView row = new TextView(ctx);
            String parent = (e.p != null && !e.p.isEmpty()) ? e.p : "";
            row.setText(e.n + "  ·  " + parent);
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(17);
            row.setPadding(20, 14, 20, 14);
            row.setClickable(true);
            row.setFocusable(true);
            final String adcode = e.a;
            final String name = e.n;
            row.setOnClickListener(v -> selectCity(name, adcode));
            list.addView(row);
        }
    }

    /** 选中城市：解析 adcode → Location ID，保存并刷新天气。 */
    private void selectCity(final String name, final String adcode) {
        final android.content.Context ctx = getContext();
        if (ctx == null || adcode == null || adcode.isEmpty()) return;

        dismissCityDialog();
        android.widget.Toast.makeText(ctx, "正在切换 " + name + "…",
                android.widget.Toast.LENGTH_SHORT).show();

        final String apiKey = PrefsHelper.getHefengKey(WeatherFetcher.DEFAULT_KEY);
        new Thread(() -> {
            try {
                WeatherFetcher.CitySuggestion s = weatherFetcher.resolveByAdcode(adcode, apiKey);
                handler.post(() -> {
                    currentCity = name;
                    currentCityId = s.locationId;
                    PrefsHelper.setCurrentCity(name);
                    PrefsHelper.setCurrentCityId(s.locationId);
                    citySelector.setText(currentCity + " ▼");
                    refreshWeather(true);
                });
            } catch (IOException e) {
                final String msg = e.getMessage();
                handler.post(() -> onWeatherError(msg));
            }
        }).start();
    }

    // ---------- 全屏 ----------

    private void toggleFullscreen() {
        if (getActivity() == null) return;
        View decorView = getActivity().getWindow().getDecorView();
        int flags = decorView.getSystemUiVisibility();

        if ((flags & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) {
            // 当前全屏 → 退出
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            // 进入全屏沉浸
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        showFloatingButtons();
    }

    // ---------- 时间重校 ----------

    private void resyncTime() {
        manualOffset = 0;
        PrefsHelper.setManualOffset(0);

        // 触发异步时间同步，手动偏移已归零
        TimeSyncHelper.syncAsync();
        android.widget.Toast.makeText(getContext(),
                "已重置偏移，时间重新校准中…", android.widget.Toast.LENGTH_SHORT).show();
        showFloatingButtons();
    }

    // ---------- 浮动按钮自动隐藏 ----------

    private void showFloatingButtons() {
        if (btnFullscreen != null) {
            btnFullscreen.animate().alpha(1f).setDuration(200).start();
        }
        if (caliBar != null) {
            caliBar.animate().alpha(1f).setDuration(200).start();
        }
        if (btnCalPrev != null) {
            btnCalPrev.animate().alpha(1f).setDuration(200).start();
        }
        if (btnCalNext != null) {
            btnCalNext.animate().alpha(1f).setDuration(200).start();
        }
        handler.removeCallbacks(hideFloatingBtnTask);
        handler.postDelayed(hideFloatingBtnTask, 5000);
    }
}
