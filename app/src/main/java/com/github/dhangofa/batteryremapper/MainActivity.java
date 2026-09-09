package com.github.dhangofa.batteryremapper;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.app.AlertDialog;
import android.widget.ImageButton;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private AppPreferences appPreferences;
    private Switch remapperSwitch;
    private Switch batterySaverSwitch;
    private Switch autoShutdownSwitch;
    private View batterySaverCard;
    private View autoShutdownCard;
    private ImageButton refreshSystemUiButton;
    
    private final ExecutorService commandExecutor =
            Executors.newSingleThreadExecutor();
    
    private volatile boolean isSystemUiRestarting = false;

    private final CompoundButton.OnCheckedChangeListener remapperListener =
            (buttonView, isChecked) -> {
                appPreferences.setRemapperEnabled(isChecked);
    
                if (!isChecked) {
                    resetAutomationControls();
                }
    
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

        refreshSystemUiButton = findViewById(R.id.btnRefreshSystemUi); 
        refreshSystemUiButton.setOnClickListener(
                view -> showRestartSystemUiDialog()
        );


        restorePreferences();
        configureCardClickTargets();
        attachListeners();
    }

    @Override
    protected void onDestroy() {
        commandExecutor.shutdownNow();
        super.onDestroy();
    }

    private void restorePreferences() {
        boolean remapperEnabled = appPreferences.isRemapperEnabled();
    
        remapperSwitch.setChecked(remapperEnabled);
    
        if (remapperEnabled) {
            batterySaverSwitch.setChecked(
                    appPreferences.isBatterySaverEnabled()
            );
    
            autoShutdownSwitch.setChecked(
                    appPreferences.isAutoShutdownEnabled()
            );
        } else {
            batterySaverSwitch.setChecked(false);
            autoShutdownSwitch.setChecked(false);
    
            appPreferences.setBatterySaverEnabled(false);
            appPreferences.setAutoShutdownEnabled(false);
        }
    
        updateChildControls(remapperEnabled);
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

    private void resetAutomationControls() {
        batterySaverSwitch.setChecked(false);
        autoShutdownSwitch.setChecked(false);
    
        appPreferences.setBatterySaverEnabled(false);
        appPreferences.setAutoShutdownEnabled(false);
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

    private void showRestartSystemUiDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
    
        if (isSystemUiRestarting) {
            Toast.makeText(
                    this,
                    R.string.restart_system_ui_busy,
                    Toast.LENGTH_SHORT
            ).show();
    
            return;
        }
    
        new AlertDialog.Builder(this)
                .setTitle(R.string.restart_system_ui_title)
                .setMessage(R.string.restart_system_ui_message)
                .setNegativeButton(
                        R.string.restart_system_ui_cancel,
                        null
                )
                .setPositiveButton(
                        R.string.restart_system_ui_confirm,
                        (dialog, which) -> restartSystemUi()
                )
                .show();
    }

    private void restartSystemUi() {
        if (isSystemUiRestarting) {
            return;
        }
    
        isSystemUiRestarting = true;
        refreshSystemUiButton.setEnabled(false);
        refreshSystemUiButton.setAlpha(0.5f);
    
        Toast.makeText(
                this,
                R.string.restart_system_ui_started,
                Toast.LENGTH_SHORT
        ).show();
    
        commandExecutor.execute(() -> {
            boolean successful = executeRootCommand(
                    "am crash com.android.systemui"
            );
    
            if (!successful) {
                successful = executeRootCommand(
                        "killall com.android.systemui"
                );
            }
    
            boolean finalSuccessful = successful;
    
            runOnUiThread(() -> {
                isSystemUiRestarting = false;
    
                if (!isFinishing() && !isDestroyed()) {
                    refreshSystemUiButton.setEnabled(true);
                    refreshSystemUiButton.setAlpha(1.0f);
    
                    if (!finalSuccessful) {
                        Toast.makeText(
                                MainActivity.this,
                                R.string.restart_system_ui_failed,
                                Toast.LENGTH_LONG
                        ).show();
                    }
                }
            });
        });
    }
    private boolean executeRootCommand(String command) {
        Process process = null;
        DataOutputStream outputStream = null;
    
        try {
            process = new ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start();
    
            outputStream = new DataOutputStream(
                    process.getOutputStream()
            );
    
            outputStream.writeBytes(command);
            outputStream.writeBytes("\n");
            outputStream.writeBytes("exit\n");
            outputStream.flush();
    
            boolean completed = process.waitFor(
                    8,
                    TimeUnit.SECONDS
            );
    
            if (!completed) {
                process.destroy();
    
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
    
                return false;
            }
    
            return process.exitValue() == 0;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
            }
    
            if (process != null && process.isAlive()) {
                process.destroy();
            }
        }
    }
}
