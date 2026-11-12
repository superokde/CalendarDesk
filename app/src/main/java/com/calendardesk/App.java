package com.calendardesk;

import android.app.Application;

import com.calendardesk.util.PrefsHelper;
import com.calendardesk.util.TimeSyncHelper;
import com.jakewharton.threetenabp.AndroidThreeTen;

/**
 * Application 入口，初始化全局单例。
 */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();

        // Java 8 时间 API 兼容（API < 26 必需）
        AndroidThreeTen.init(this);

        // SharedPreferences 封装
        PrefsHelper.init(this);

        // 时间同步（HTTP Date 头多源投票，与 Web 版一致）
        TimeSyncHelper.init(new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build());
    }
}
