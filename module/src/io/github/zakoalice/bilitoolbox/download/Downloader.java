package io.github.zakoalice.bilitoolbox.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.XLog;

/**
 * 下载 + 落盘。
 *
 * <p>视频地址是带时效的（一般两小时），而且必须要 {@code Referer: bilibili.com} 才会给数据，
 * 所以不能把这个 URL 丢给系统的 DownloadManager —— 它加不上 Referer。这里自己用
 * HttpURLConnection 拉，先落到临时文件，成功了再进媒体库/公共目录，失败不会留下半个文件。
 *
 * <p>落盘位置：Android 10+ 走 MediaStore.Downloads（不需要存储权限，文件管理器里能看到）；
 * 更老的系统直接写 /sdcard/Download/xxx（靠哔哩哔哩自己的存储权限）。
 *
 * <p>所有任务串行：同时下几个大文件只会互相抢带宽，还把进度界面搞乱。
 */
public final class Downloader {

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Downloader() {
    }

    public interface Callback {
        /** 已下载 / 总大小（总大小未知时为 0）。主线程回调。 */
        void onProgress(long received, long total);

        /** 成功，savedTo 是给人看的位置描述。 */
        void onDone(String savedTo);

        void onError(String message);
    }

    public static void enqueue(final Context context, final String url, final String fileName,
                               final String mime, final Callback callback) {
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String saved = download(context, url, fileName, mime, callback);
                    final String message = saved;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onDone(message);
                        }
                    });
                } catch (Throwable t) {
                    XLog.e("下载失败 " + fileName, t);
                    final String message = t.getMessage() == null ? t.toString() : t.getMessage();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onError(message);
                        }
                    });
                }
            }
        });
    }

    private static String download(Context context, String url, String fileName, String mime,
                                   Callback callback) throws IOException {
        File tmp = new File(context.getCacheDir(), "bt_" + System.currentTimeMillis() + ".part");
        HttpURLConnection conn = null;
        long total = 0;
        long received = 0;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", UA);
            conn.setRequestProperty("Referer", "https://www.bilibili.com/");
            String cookie = BiliApi.cookie();
            if (cookie != null && cookie.length() > 0) {
                conn.setRequestProperty("Cookie", cookie);
            }
            int code = conn.getResponseCode();
            if (code != 200 && code != 206) {
                throw new IOException("服务器返回 HTTP " + code);
            }
            total = conn.getContentLengthLong();

            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            long lastNotify = 0;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                received += n;
                long now = System.currentTimeMillis();
                if (now - lastNotify > 200) {
                    lastNotify = now;
                    notifyProgress(callback, received, total);
                }
            }
            out.flush();
            out.close();
            in.close();
            notifyProgress(callback, received, total);

            if (received <= 0) {
                throw new IOException("下载到 0 字节");
            }
            return save(context, tmp, fileName, mime);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            // 无论成败都不留临时文件
            if (tmp.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static void notifyProgress(final Callback callback, final long received, final long total) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onProgress(received, total);
            }
        });
    }

    /** 把临时文件搬进公共下载目录，返回可读位置。 */
    private static String save(Context context, File tmp, String fileName, String mime) throws IOException {
        String folder = Prefs.folder();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IOException("无法在下载目录创建文件");
            }
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new java.io.FileInputStream(tmp);
                out = resolver.openOutputStream(uri);
                if (out == null) {
                    throw new IOException("打开输出流失败");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
            values.clear();
            values.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, values, null, null);
            return folder + "/" + fileName;
        }

        File dir = new File(Environment.getExternalStorageDirectory(), folder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("创建目录失败：" + dir);
        }
        File target = new File(dir, fileName);
        if (!tmp.renameTo(target)) {
            // 跨文件系统时 rename 会失败，退回复制
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new java.io.FileInputStream(tmp);
                out = new FileOutputStream(target);
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } finally {
                closeQuietly(in);
                closeQuietly(out);
            }
        }
        return target.getAbsolutePath();
    }

    /** 文件名清洗：去掉系统不认的字符，并限制长度。 */
    public static String safeName(String raw) {
        if (raw == null || raw.trim().length() == 0) {
            return "video_" + System.currentTimeMillis();
        }
        String cleaned = raw.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").trim();
        if (cleaned.length() > 120) {
            cleaned = cleaned.substring(0, 120);
        }
        return cleaned;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) {
            return;
        }
        try {
            c.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    public static String humanSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        return String.format(java.util.Locale.US, "%.2f GB", bytes / 1073741824.0);
    }
}
