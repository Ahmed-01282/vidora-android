package com.vidora.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;
import com.vidora.app.R;

import java.io.File;

import dev.ffmpegkit_maintained.ytdlp.DownloadProgressCallback;
import dev.ffmpegkit_maintained.ytdlp.YtDlp;
import dev.ffmpegkit_maintained.ytdlp.YtDlpException;
import dev.ffmpegkit_maintained.ytdlp.YtDlpRequest;
import dev.ffmpegkit_maintained.ytdlp.YtDlpResponse;

public class MainActivity extends Activity {
    private EditText urlInput;
    private Button downloadButton;
    private ProgressBar progressBar;
    private TextView statusText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        urlInput = findViewById(R.id.urlInput);
        downloadButton = findViewById(R.id.downloadButton);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);

        MobileAds.initialize(this, initializationStatus -> {});
        com.google.android.gms.ads.AdView banner = findViewById(R.id.bannerAd);
        banner.loadAd(new AdRequest.Builder().build());

        try {
            YtDlp.init(this);
            statusText.setText("محرك yt-dlp جاهز محليًا");
        } catch (YtDlpException e) {
            statusText.setText("فشل تهيئة المحرك: " + e.getMessage());
        }

        downloadButton.setOnClickListener(v -> startDownload());
    }

    private void startDownload() {
        final String url = urlInput.getText().toString().trim();
        if (url.length() == 0 || !url.startsWith("http")) {
            statusText.setText("أدخل رابط HTTPS صحيحًا");
            return;
        }

        downloadButton.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        statusText.setText("جاري الاستخراج والتنزيل...");

        File downloadDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Vidora");
        if (!downloadDir.exists()) downloadDir.mkdirs();

        String output = new File(downloadDir, "%(title).80s.%(ext)s").getAbsolutePath();
        YtDlpRequest request = new YtDlpRequest(url)
                .setOutputTemplate(output)
                .addOption("-f", "best[height<=720]/best")
                .addOption("--no-playlist");

        YtDlp.executeAsync(request, new DownloadProgressCallback() {
            @Override
            public void onProgressUpdate(float progress, long etaInSeconds, String line) {
                runOnUiThread(() -> {
                    progressBar.setProgress(Math.max(0, Math.min(100, Math.round(progress))));
                    statusText.setText("التقدم: " + Math.round(progress) + "%");
                });
            }
        });
    }
}
