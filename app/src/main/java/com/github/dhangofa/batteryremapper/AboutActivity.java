package com.github.dhangofa.batteryremapper;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

public class AboutActivity extends Activity {

    private static final String URL_GITHUB_REPO = "https://github.com/Dhangofa/BatteryRemapper/";
    private static final String URL_TELEGRAM_GROUP = "https://t.me/dhangofas_projects_chat";
    private static final String URL_DEVELOPER_GITHUB = "https://github.com/Dhangofa";
    private static final String URL_CONTRIBUTOR_GITHUB = "https://github.com/Rillwyn";
    private static final String URL_LICENSE = "https://github.com/Dhangofa/BatteryRemapper/blob/main/LICENSE";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> btnBack.post(this::finishAfterTransition));

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    this::finishAfterTransition
            );
        }

        TextView aboutVersionText = findViewById(R.id.aboutVersionText);
        String versionName = resolveVersionName();
        aboutVersionText.setText(getString(R.string.app_version_format, versionName));

        View pillLicense = findViewById(R.id.pillLicense);
        pillLicense.setOnClickListener(v -> openUrl(URL_LICENSE));

        ImageButton btnGithubRepo = findViewById(R.id.btnGithubRepo);
        btnGithubRepo.setOnClickListener(v -> openUrl(URL_GITHUB_REPO));

        ImageButton btnTelegramGroup = findViewById(R.id.btnTelegramGroup);
        btnTelegramGroup.setOnClickListener(v -> openUrl(URL_TELEGRAM_GROUP));

        View cardDeveloper = findViewById(R.id.cardDeveloper);
        ImageButton btnDeveloperGithub = findViewById(R.id.btnDeveloperGithub);
        View.OnClickListener devListener = v -> openUrl(URL_DEVELOPER_GITHUB);
        cardDeveloper.setOnClickListener(devListener);
        btnDeveloperGithub.setOnClickListener(devListener);

        View cardContributor = findViewById(R.id.cardContributor);
        ImageButton btnContributorGithub = findViewById(R.id.btnContributorGithub);
        View.OnClickListener contribListener = v -> openUrl(URL_CONTRIBUTOR_GITHUB);
        cardContributor.setOnClickListener(contribListener);
        btnContributorGithub.setOnClickListener(contribListener);
    }

    private String resolveVersionName() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            if (packageInfo != null && packageInfo.versionName != null && !packageInfo.versionName.isEmpty()) {
                return packageInfo.versionName;
            }
        } catch (Exception ignored) {
        }
        return "1.0";
    }

    private void openUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show();
        }
    }
}
