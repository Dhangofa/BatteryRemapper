package com.github.dhangofa.batteryremapper;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {

    private AppPreferences appPreferences;
    private Switch remapperSwitch;
    private Switch batterySaverSwitch;
    private Switch autoShutdownSwitch;
    private View batterySaverCard;
    private View autoShutdownCard;

    private final CompoundButton.OnCheckedChangeListener remapperListener =
            (buttonView, isChecked) -> {
                appPreferences.setRemapperEnabled(isChecked);
                updateChildControls(isChecked);
            };

    private final CompoundButton.OnCheckedChangeListener batterySaverListener =
            (buttonView, isChecked) -> appPreferences.setBatterySaverEnabled(isChecked);

    private final CompoundButton.OnCheckedChangeListener autoShutdownListener =
            (buttonView, isChecked) -> appPreferences.setAutoShutdownEnabled(isChecked);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        appPreferences = new AppPreferences(this);

        remapperSwitch = findViewById(R.id.switchBatteryRemapper);
        batterySaverSwitch = findViewById(R.id.switchBatterySaver);
        autoShutdownSwitch = findViewById(R.id.switchAutoShutdown);
        batterySaverCard = findViewById(R.id.cardBatterySaver);
        autoShutdownCard = findViewById(R.id.cardAutoShutdown);

        TextView appVersionText = findViewById(R.id.appVersionText);
        appVersionText.setText(getString(R.string.app_version_format, resolveVersionName()));

        restorePreferences();
        configureCardClickTargets();
        attachListeners();
    }

    private void restorePreferences() {
        remapperSwitch.setChecked(appPreferences.isRemapperEnabled());
        batterySaverSwitch.setChecked(appPreferences.isBatterySaverEnabled());
        autoShutdownSwitch.setChecked(appPreferences.isAutoShutdownEnabled());
        updateChildControls(remapperSwitch.isChecked());
    }

    private void attachListeners() {
        remapperSwitch.setOnCheckedChangeListener(remapperListener);
        batterySaverSwitch.setOnCheckedChangeListener(batterySaverListener);
        autoShutdownSwitch.setOnCheckedChangeListener(autoShutdownListener);
    }

    private void configureCardClickTargets() {
        findViewById(R.id.cardBatteryRemapper).setOnClickListener(
                view -> remapperSwitch.toggle()
        );
        batterySaverCard.setOnClickListener(view -> {
            if (batterySaverSwitch.isEnabled()) batterySaverSwitch.toggle();
        });
        autoShutdownCard.setOnClickListener(view -> {
            if (autoShutdownSwitch.isEnabled()) autoShutdownSwitch.toggle();
        });
    }

    private void updateChildControls(boolean masterEnabled) {
        setChildControlState(batterySaverCard, batterySaverSwitch, masterEnabled);
        setChildControlState(autoShutdownCard, autoShutdownSwitch, masterEnabled);
    }

    private void setChildControlState(View card, Switch toggle, boolean enabled) {
        card.setEnabled(enabled);
        toggle.setEnabled(enabled);
        card.setAlpha(enabled ? 1.0f : 0.52f);
    }

    private String resolveVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName != null ? packageInfo.versionName : "1.0";
        } catch (Exception ignored) {
            return "1.0";
        }
    }
}
