package com.calendardesk;

import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.calendardesk.fragment.MainFragment;

/**
 * 唯一主 Activity：全屏沉浸 + 常亮 + 触摸自动显示悬浮按钮。
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 屏幕常亮（台历核心需求）
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // 全屏沉浸模式
        hideSystemUI();

        // 添加主界面 Fragment
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .add(R.id.fragment_container, new MainFragment())
                    .commit();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUI();
    }

    /**
     * 沉浸模式：隐藏状态栏 + 导航栏，下滑/点击可短暂唤出。
     */
    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    /**
     * 触摸屏幕时通知 Fragment 显示浮动按钮。
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            Fragment f = getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_container);
            if (f instanceof MainFragment) {
                ((MainFragment) f).onUserInteraction();
            }
        }
        return super.dispatchTouchEvent(ev);
    }

    /**
     * 打开设置页。
     */
    public void openSettings() {
        startActivity(new android.content.Intent(this, SettingsActivity.class));
    }
}
