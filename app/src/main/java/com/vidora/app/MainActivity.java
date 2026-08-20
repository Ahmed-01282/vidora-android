package com.vidora.app;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.google.android.gms.ads.AdRequest;
import com.google.android.gms.ads.MobileAds;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.Future;

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
        statusText.setText("جاري استخراج الفيديو وتنزيله...");

        final File privateDownloadDir = new File(
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "Vidora"
        );
        if (!privateDownloadDir.exists() && !privateDownloadDir.mkdirs()) {
            statusText.setText("تعذر إنشاء مجلد التنزيل المؤقت");
            downloadButton.setEnabled(true);
            return;
        }

        final String output = new File(
                privateDownloadDir,
                "%(title).80s.%(ext)s"
        ).getAbsolutePath();

        final boolean isFacebook = isFacebookUrl(url);
        final String normalizedUrl = isFacebook ? url : normalizeNonFacebookUrl(url);

        final YtDlpRequest request = new YtDlpRequest(normalizedUrl)
                .setOutputTemplate(output)
                .addOption("--no-playlist")
                .addOption("--no-part")
                .addOption("--no-keep-video");

        if (isFacebook) {
            // Facebook path is intentionally unchanged because it already works.
            request.addOption("-f", "best[height<=720]/best");
        } else {
            // Non-Facebook path: prefer one combined MP4 format so this build
            // does not fail when an FFmpeg binary is not bundled in the APK.
            request.addOption("-f", "best[ext=mp4][height<=720]/best[height<=720]/best");
            request.addOption("--retries", "3");
            request.addOption("--fragment-retries", "3");
            request.addOption("--extractor-retries", "3");
            request.addOption("--socket-timeout", "30");
            request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36");

            String lowerUrl = normalizedUrl.toLowerCase();
            if (lowerUrl.contains("instagram.com")) {
                request.addOption("--referer", "https://www.instagram.com/");
            } else if (lowerUrl.contains("tiktok.com")) {
                request.addOption("--referer", "https://www.tiktok.com/");
                request.addOption("--force-ipv4");
            } else if (lowerUrl.contains("youtube.com") || lowerUrl.contains("youtu.be")) {
                request.addOption("--referer", "https://www.youtube.com/");
            }

            // Optional user-owned cookies file. Place it at:
            // Android/data/com.vidora.app/files/Download/cookies.txt
            File cookiesFile = new File(
                    getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                    "cookies.txt"
            );
            if (cookiesFile.isFile() && cookiesFile.length() > 0
                    && (lowerUrl.contains("instagram.com")
                    || lowerUrl.contains("youtube.com")
                    || lowerUrl.contains("youtu.be"))) {
                request.addOption("--cookies", cookiesFile.getAbsolutePath());
            }
        }

        try {
            final Future<YtDlpResponse> downloadFuture = YtDlp.executeAsync(
                    request,
                    new DownloadProgressCallback() {
                        @Override
                        public void onProgressUpdate(
                                float progress,
                                long etaInSeconds,
                                String line
                        ) {
                            runOnUiThread(() -> {
                                int value = Math.max(
                                        0,
                                        Math.min(100, Math.round(progress))
                                );
                                progressBar.setProgress(value);
                                statusText.setText("التقدم: " + value + "%");
                            });
                        }
                    }
            );

            new Thread(() -> {
                try {
                    downloadFuture.get();
                    File downloadedFile = findNewestVideo(privateDownloadDir);
                    if (downloadedFile == null || !downloadedFile.isFile()) {
                        throw new Exception("لم يتم العثور على ملف الفيديو بعد التنزيل");
                    }

                    Uri savedUri = saveVideoToGallery(downloadedFile);

                    runOnUiThread(() -> {
                        progressBar.setProgress(100);
                        statusText.setText(
                                "تم الحفظ في المعرض داخل Movies/Vidora"
                        );
                        downloadButton.setEnabled(true);
                    });

                    if (savedUri != null) {
                        downloadedFile.delete();
                    }
                } catch (final Exception error) {
                    runOnUiThread(() -> {
                        progressBar.setProgress(0);
                        statusText.setText(
                                "فشل الحفظ: " + safeMessage(error)
                        );
                        downloadButton.setEnabled(true);
                    });
                }
            }).start();
        } catch (final Exception error) {
            progressBar.setProgress(0);
            statusText.setText("تعذر بدء التنزيل: " + safeMessage(error));
            downloadButton.setEnabled(true);
        }
    }

    private boolean isFacebookUrl(String value) {
        String lower = value.toLowerCase();
        return lower.contains("facebook.com") || lower.contains("fb.watch");
    }

    private String normalizeNonFacebookUrl(String value) {
        String trimmed = value.trim();
        String lower = trimmed.toLowerCase();

        if (lower.contains("instagram.com/share/reel/")) {
            int start = lower.indexOf("instagram.com/share/reel/") + "instagram.com/share/reel/".length();
            int end = trimmed.indexOf("?", start);
            if (end < 0) end = trimmed.indexOf("/", start);
            if (end < 0) end = trimmed.length();
            String id = trimmed.substring(start, end);
            if (!id.isEmpty()) return "https://www.instagram.com/reel/" + id + "/";
        }

        if (lower.contains("instagram.com/share/p/")) {
            int start = lower.indexOf("instagram.com/share/p/") + "instagram.com/share/p/".length();
            int end = trimmed.indexOf("?", start);
            if (end < 0) end = trimmed.indexOf("/", start);
            if (end < 0) end = trimmed.length();
            String id = trimmed.substring(start, end);
            if (!id.isEmpty()) return "https://www.instagram.com/p/" + id + "/";
        }

        return trimmed;
    }

    private File findNewestVideo(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return null;

        File newest = null;
        for (File file : files) {
            if (!file.isFile()) continue;
            String name = file.getName().toLowerCase();
            if (name.endsWith(".mp4")
                    || name.endsWith(".webm")
                    || name.endsWith(".mkv")
                    || name.endsWith(".mov")
                    || name.endsWith(".m4v")) {
                if (newest == null
                        || file.lastModified() > newest.lastModified()) {
                    newest = file;
                }
            }
        }
        return newest;
    }

    private Uri saveVideoToGallery(File source) throws Exception {
        String mimeType = getMimeType(source.getName());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, mimeType);
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Vidora"
            );
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            Uri collection = MediaStore.Video.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
            );
            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new Exception("تعذر إنشاء ملف في المعرض");
            }

            try {
                copyFileToUri(source, uri);
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Video.Media.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
                return uri;
            } catch (Exception error) {
                resolver.delete(uri, null, null);
                throw error;
            }
        }

        File legacyDirectory = new File(
                Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_MOVIES
                ),
                "Vidora"
        );
        if (!legacyDirectory.exists() && !legacyDirectory.mkdirs()) {
            throw new Exception("تعذر إنشاء مجلد Movies/Vidora");
        }

        File destination = new File(legacyDirectory, source.getName());
        copyFile(source, destination);
        return Uri.fromFile(destination);
    }

    private void copyFileToUri(File source, Uri destination) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = getContentResolver().openOutputStream(destination)) {
            if (output == null) throw new Exception("تعذر فتح ملف المعرض");
            copyStream(input, output);
        }
    }

    private void copyFile(File source, File destination) throws Exception {
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(destination)) {
            copyStream(input, output);
        }
    }

    private void copyStream(InputStream input, OutputStream output)
            throws Exception {
        byte[] buffer = new byte[1024 * 64];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        output.flush();
    }

    private String getMimeType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".mkv")) return "video/x-matroska";
        if (name.endsWith(".mov")) return "video/quicktime";
        return "video/mp4";
    }

    private String safeMessage(Exception error) {
        if (error.getMessage() == null || error.getMessage().trim().isEmpty()) {
            return "خطأ غير معروف";
        }
        return error.getMessage();
    }
}
