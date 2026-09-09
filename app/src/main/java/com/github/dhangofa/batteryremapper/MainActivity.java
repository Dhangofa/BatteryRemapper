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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.UUID;

public class MainActivity extends Activity {

    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";
    private static final String MODULE_PACKAGE = "com.github.dhangofa.batteryremapper";
    private static final String ACTION_PROBE_HOOK = MODULE_PACKAGE + ".action.PROBE_SYSTEMUI_HOOK";
    private static final String ACTION_HOOK_STATUS = MODULE_PACKAGE + ".action.SYSTEMUI_HOOK_STATUS";
    private static final String EXTRA_REQUEST_ID = MODULE_PACKAGE + ".extra.REQUEST_ID";
    private static final String EXTRA_HOOK_ACTIVE = MODULE_PACKAGE + ".extra.HOOK_ACTIVE";
    private static final String EXTRA_HOOKED_PACKAGE = MODULE_PACKAGE + ".extra.HOOKED_PACKAGE";
    private static final long HOOK_STATUS_TIMEOUT_MS = 1500L;

    private static final String ACTION_SETTINGS_CHANGED = MODULE_PACKAGE + ".action.SETTINGS_CHANGED";
    private boolean suppressToggleCallbacks = false;

    private View hookStatusCard;
    private View hookStatusDot;
    private TextView hookStatusTitle;
    private TextView hookStatusDescription;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private boolean statusReceiverRegistered = false;
    private String activeStatusRequestId;
    
    private AppPreferences appPreferences;
    private Switch remapperSwitch;
    private Switch batterySaverSwitch;
    private Switch autoShutdownSwitch;
    private View batterySaverCard;
    private View autoShutdownCard;
    
    private ImageButton refreshSystemUiButton;
    private final ExecutorService commandExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean isSystemUiRestarting = false;

    private final CompoundButton.OnCheckedChangeListener remapperListener =
            (buttonView, isChecked) -> {
                if (suppressToggleCallbacks) {
                    return;
                }
    
                if (isChecked) {
                    appPreferences.setRemapperEnabled(true);
                    updateChildControls(true);
                } else {
                    appPreferences.disableAllFeatures();
                    resetAutomationControls();
                    updateChildControls(false);
                }
    
                showRemapperRefreshRequiredDialog(isChecked);
            };
    
    private final CompoundButton.OnCheckedChangeListener batterySaverListener =
            (buttonView, isChecked) -> {
                if (suppressToggleCallbacks) {
                    return;
                }
    
                appPreferences.setBatterySaverEnabled(isChecked);
                notifySystemUiSettingsChanged();
            };
    
    private final CompoundButton.OnCheckedChangeListener autoShutdownListener =
            (buttonView, isChecked) -> {
                if (suppressToggleCallbacks) {
                    return;
                }
    
                appPreferences.setAutoShutdownEnabled(isChecked);
                notifySystemUiSettingsChanged();
            };
    private final BroadcastReceiver hookStatusReceiver =
        new BroadcastReceiver() {
            @Override
            public void onReceive(
                    Context context,
                    Intent intent
            ) {
                if (intent == null
                        || !ACTION_HOOK_STATUS.equals(
                                intent.getAction()
                        )) {
                    return;
                }

                String responseRequestId =
                        intent.getStringExtra(EXTRA_REQUEST_ID);

                if (activeStatusRequestId == null
                        || !activeStatusRequestId.equals(
                                responseRequestId
                        )) {
                    return;
                }

                boolean hookActive = intent.getBooleanExtra(
                        EXTRA_HOOK_ACTIVE,
                        false
                );

                String hookedPackage = intent.getStringExtra(
                        EXTRA_HOOKED_PACKAGE
                );

                if (!hookActive
                        || !SYSTEM_UI_PACKAGE.equals(hookedPackage)) {
                    return;
                }

                statusHandler.removeCallbacks(
                        hookStatusTimeoutRunnable
                );

                activeStatusRequestId = null;
                showHookActive();
            }
        };

    private final Runnable hookStatusTimeoutRunnable = () -> {
        if (activeStatusRequestId == null) {
            return;
        }
    
        activeStatusRequestId = null;
        showHookInactive();
    };

    

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        hookStatusCard = findViewById(R.id.cardHookStatus);
        hookStatusDot = findViewById(R.id.hookStatusDot);
        hookStatusTitle = findViewById(R.id.hookStatusTitle);
        hookStatusDescription = findViewById(R.id.hookStatusDescription);
        hookStatusCard.setOnClickListener(
                view -> checkSystemUiHook()
        );

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

    @Override
    protected void onStart() {
        super.onStart();
    
        registerHookStatusReceiver();
        checkSystemUiHook();
    }

    @Override
    protected void onStop() {
        statusHandler.removeCallbacks(
                hookStatusTimeoutRunnable
        );
    
        activeStatusRequestId = null;
        unregisterHookStatusReceiver();
    
        super.onStop();
    }

    private void registerHookStatusReceiver() {
        if (statusReceiverRegistered) {
            return;
        }
    
        IntentFilter filter =
                new IntentFilter(ACTION_HOOK_STATUS);
    
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                    hookStatusReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
            );
        } else {
            registerReceiver(
                    hookStatusReceiver,
                    filter
            );
        }
    
        statusReceiverRegistered = true;
    }

    private void unregisterHookStatusReceiver() {
        if (!statusReceiverRegistered) {
            return;
        }
    
        try {
            unregisterReceiver(hookStatusReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was already removed by the system.
        }
    
        statusReceiverRegistered = false;
    }

    private void checkSystemUiHook() {
        statusHandler.removeCallbacks(
                hookStatusTimeoutRunnable
        );
    
        activeStatusRequestId =
                UUID.randomUUID().toString();
    
        showHookChecking();
    
        Intent probeIntent =
                new Intent(ACTION_PROBE_HOOK);
    
        // Restrict delivery to a receiver inside SystemUI.
        probeIntent.setPackage(SYSTEM_UI_PACKAGE);
    
        probeIntent.putExtra(
                EXTRA_REQUEST_ID,
                activeStatusRequestId
        );
    
        try {
            sendBroadcast(probeIntent);
    
            statusHandler.postDelayed(
                    hookStatusTimeoutRunnable,
                    HOOK_STATUS_TIMEOUT_MS
            );
        } catch (Throwable ignored) {
            activeStatusRequestId = null;
            showHookInactive();
        }
    }

    private void showHookChecking() {
        hookStatusTitle.setText(
                R.string.status_checking_title
        );
    
        hookStatusDescription.setText(
                R.string.status_checking_description
        );
    
        applyStatusColors(
                R.color.status_checking,
                R.color.status_card_checking
        );
    }

    private void showHookActive() {
        hookStatusTitle.setText(
                R.string.status_active_title
        );
    
        hookStatusDescription.setText(
                R.string.status_active_description
        );
    
        applyStatusColors(
                R.color.status_active,
                R.color.status_card_active
        );
    }

    private void showHookInactive() {
        hookStatusTitle.setText(
                R.string.status_inactive_title
        );
    
        hookStatusDescription.setText(
                getString(
                        R.string.status_inactive_description
                ) + "\n" + getString(
                        R.string.status_tap_to_check
                )
        );
    
        applyStatusColors(
                R.color.status_inactive,
                R.color.status_card_inactive
        );
    }

    private void applyStatusColors(
            int dotColorResource,
            int cardColorResource
    ) {
        hookStatusDot.setBackgroundTintList(
                ColorStateList.valueOf(
                        getColor(dotColorResource)
                )
        );
    
        hookStatusCard.setBackgroundTintList(
                ColorStateList.valueOf(
                        getColor(cardColorResource)
                )
        );
    }
    
    private void restorePreferences() {
        boolean remapperEnabled =
                appPreferences.isRemapperEnabled();
    
        suppressToggleCallbacks = true;
    
        try {
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
            }
        } finally {
            suppressToggleCallbacks = false;
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
        suppressToggleCallbacks = true;
    
        try {
            batterySaverSwitch.setChecked(false);
            autoShutdownSwitch.setChecked(false);
        } finally {
            suppressToggleCallbacks = false;
        }
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

    private void notifySystemUiSettingsChanged() {
        Intent intent =
                new Intent(ACTION_SETTINGS_CHANGED);
    
        /*
         * Restrict delivery to the receiver registered inside
         * com.android.systemui.
         */
        intent.setPackage(SYSTEM_UI_PACKAGE);
    
        try {
            sendBroadcast(intent);
        } catch (Throwable ignored) {
            /*
             * The preference remains saved. If SystemUI is not currently
             * hooked, the setting will be loaded when SystemUI starts.
             */
        }
    }

    private void showRemapperRefreshRequiredDialog(
            boolean enabled
    ) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
    
        int messageResource =
                enabled
                        ? R.string.remapper_enabled_refresh_message
                        : R.string.remapper_disabled_refresh_message;
    
        new AlertDialog.Builder(this)
                .setTitle(
                        R.string.system_ui_refresh_required_title
                )
                .setMessage(messageResource)
                .setNegativeButton(
                        R.string.restart_system_ui_later,
                        null
                )
                .setPositiveButton(
                        R.string.restart_system_ui_now,
                        (dialog, which) -> restartSystemUi()
                )
                .show();
    }
}
