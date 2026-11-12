package com.calendardesk.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.calendardesk.R;
import com.calendardesk.SettingsActivity;
import com.calendardesk.util.PrefsHelper;
import com.calendardesk.util.WeatherFetcher;

import java.util.List;

/**
 * 设置页面：和风天气 API Key 管理 + 手动校准 + 城市选择。
 */
public class SettingsFragment extends Fragment {

    private EditText etApiKey;
    private TextView tvOffset;
    private TextView tvSync;
    private LinearLayout cityListSettings;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 返回按钮
        TextView btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() instanceof SettingsActivity) {
                ((SettingsActivity) getActivity()).goBack();
            }
        });

        // API Key
        etApiKey = view.findViewById(R.id.et_api_key);
        etApiKey.setText(PrefsHelper.getHefengKey(WeatherFetcher.DEFAULT_KEY));

        TextView btnReset = view.findViewById(R.id.btn_reset_key);
        btnReset.setOnClickListener(v -> {
            etApiKey.setText(WeatherFetcher.DEFAULT_KEY);
        });

        TextView btnSave = view.findViewById(R.id.btn_save_key);
        btnSave.setOnClickListener(v -> saveApiKey());

        // 手动校准
        tvOffset = view.findViewById(R.id.tv_offset_status);
        tvSync = view.findViewById(R.id.tv_sync_status);
        updateOffsetDisplay();

        TextView btnPlus = view.findViewById(R.id.btn_plus);
        btnPlus.setOnClickListener(v -> {
            long offset = PrefsHelper.getManualOffset() + 1000;
            PrefsHelper.setManualOffset(offset);
            updateOffsetDisplay();
            Toast.makeText(getContext(), "+1.0 秒", Toast.LENGTH_SHORT).show();
        });

        TextView btnMinus = view.findViewById(R.id.btn_minus);
        btnMinus.setOnClickListener(v -> {
            long offset = PrefsHelper.getManualOffset() - 1000;
            PrefsHelper.setManualOffset(offset);
            updateOffsetDisplay();
            Toast.makeText(getContext(), "-1.0 秒", Toast.LENGTH_SHORT).show();
        });

        TextView btnResetOffset = view.findViewById(R.id.btn_reset);
        btnResetOffset.setOnClickListener(v -> {
            PrefsHelper.setManualOffset(0);
            updateOffsetDisplay();
            Toast.makeText(getContext(), "已重置偏移", Toast.LENGTH_SHORT).show();
        });

        // 城市选择
        cityListSettings = view.findViewById(R.id.city_list_settings);
        buildCityChips();
    }

    private void saveApiKey() {
        if (etApiKey == null) return;

        String key = etApiKey.getText().toString().trim();
        if (key.isEmpty()) {
            Toast.makeText(getContext(), "API Key 不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        if (key.length() != 32) {
            Toast.makeText(getContext(), "Key 格式不正确（应为32位字符串）", Toast.LENGTH_LONG).show();
            return;
        }

        PrefsHelper.setHefengKey(key);
        Toast.makeText(getContext(), "Key 已保存", Toast.LENGTH_SHORT).show();

        // 返回主界面以触发天气刷新
        if (getActivity() instanceof SettingsActivity) {
            ((SettingsActivity) getActivity()).goBack();
        }
    }

    private void updateOffsetDisplay() {
        if (tvOffset == null) return;
        long offset = PrefsHelper.getManualOffset();
        float seconds = offset / 1000f;
        tvOffset.setText("当前偏移: " + (offset >= 0 ? "+" : "") + seconds + " 秒");

        if (tvSync != null) {
            tvSync.setText("同步状态: HTTP Date 头多源投票 (阿里云/百度/腾讯/timeapi.io)");
        }
    }

    private void buildCityChips() {
        if (cityListSettings == null || getContext() == null) return;
        cityListSettings.removeAllViews();

        String currentCity = PrefsHelper.getCurrentCity("东莞");
        List<String> cities = WeatherFetcher.getCities();

        for (final String city : cities) {
            TextView chip = new TextView(getContext());
            chip.setText(city);
            chip.setTextColor(city.equals(currentCity) ? 0xFFFFFFFF : 0xFF888888);
            chip.setTextSize(15);
            chip.setPadding(20, 10, 20, 10);
            chip.setBackgroundResource(
                    city.equals(currentCity) ? R.drawable.circle_bg : R.drawable.cali_btn_bg);
            chip.setClickable(true);
            chip.setFocusable(true);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(12);
            chip.setLayoutParams(params);

            chip.setOnClickListener(v -> {
                PrefsHelper.setCurrentCity(city);
                PrefsHelper.setCurrentCityId(""); // 清空 ID，主界面返回后重新解析
                Toast.makeText(getContext(), "默认城市: " + city, Toast.LENGTH_SHORT).show();
                buildCityChips(); // 刷新选中状态
            });

            cityListSettings.addView(chip);
        }
    }
}
