package io.github.zakoalice.bilitoolbox.download;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.XLog;
import io.github.zakoalice.bilitoolbox.ui.Ui;

/**
 * 视频下载器的交互层。
 *
 * <p>入口不在这里 —— 它挂在「分享」面板里（见 {@code share/ShareEntry}），点进来才调
 * {@link #openPicker(Activity)}。这里负责：从当前页面读出视频 ID、列出真实可用清晰度、
 * 起下载任务、显示进度。
 *
 * <p>没碰播放器内核（theseus / theseus2 那套每周都在变，不值得押）。视频 ID 从 Intent 取，
 * 地址走 HTTP 的 playurl 接口 —— 和浏览器扩展是同一套逻辑，只是搬进了 App。
 */
public final class VideoDownloader {

    private static final Pattern BV = Pattern.compile("(BV[0-9A-Za-z]{10})");
    private static final Pattern DIGITS = Pattern.compile("(\\d{2,})");

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private VideoDownloader() {
    }

    // ---------------------------------------------------------------- 取视频 ID

    private static final class Ids {
        long aid;
        long cid;
        String bvid;
    }

    private static Ids readIds(Activity activity) {
        Ids ids = new Ids();
        Intent intent = activity.getIntent();
        if (intent == null) {
            return ids;
        }
        Bundle extras = null;
        try {
            extras = intent.getExtras();
        } catch (Throwable t) {
            XLog.w("读 Intent extras 失败：" + t);
        }
        if (extras != null) {
            for (String key : extras.keySet()) {
                Object value;
                try {
                    value = extras.get(key);
                } catch (Throwable t) {
                    continue;
                }
                String k = key.toLowerCase(Locale.US);
                if (value instanceof Number) {
                    long n = ((Number) value).longValue();
                    if (n <= 0) {
                        continue;
                    }
                    if (k.equals("aid") || k.equals("avid")) {
                        ids.aid = n;
                    } else if (k.equals("cid")) {
                        ids.cid = n;
                    }
                } else if (value instanceof String) {
                    String s = (String) value;
                    if (k.contains("bvid") && s.startsWith("BV")) {
                        ids.bvid = s;
                    }
                }
            }
        }
        Uri data = intent.getData();
        if (data != null) {
            String path = data.getPath();
            if (path != null) {
                Matcher m = BV.matcher(path);
                if (m.find() && (ids.bvid == null || ids.bvid.length() == 0)) {
                    ids.bvid = m.group(1);
                }
                if (ids.aid == 0) {
                    Matcher digits = DIGITS.matcher(path);
                    if (digits.find()) {
                        ids.aid = Long.parseLong(digits.group(1));
                    }
                }
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- 画质选择

    public static void openPicker(final Activity activity) {
        openPicker(activity, 0L, null);
    }

    /**
     * @param knownAid  分享参数里带的 aid（0 = 没有，退回读 Intent）
     * @param knownBvid 同上
     *
     * <p>有些入口（竖屏播放、推荐流里直接分享）Intent 里一个视频字段都没有，
     * 但分享面板自己的参数里是有的，所以优先用调用方递过来的。
     */
    public static void openPicker(final Activity activity, final long knownAid, final String knownBvid) {
        final Ids ids = readIds(activity);
        if (ids.aid == 0 && knownAid > 0) {
            ids.aid = knownAid;
        }
        if ((ids.bvid == null || ids.bvid.length() == 0) && knownBvid != null && knownBvid.startsWith("BV")) {
            ids.bvid = knownBvid;
        }
        if (ids.aid == 0 && (ids.bvid == null || ids.bvid.length() == 0)) {
            toast(activity, "这里读不到视频信息，请在视频播放页里点分享 → 下载视频");
            XLog.w("取不到 aid/bvid：Intent 和分享参数里都没有视频 id");
            return;
        }
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        final LinearLayout root = Ui.column(activity);
        root.setBackground(Ui.roundRect(activity, Ui.BG, 16f));

        LinearLayout bar = Ui.row(activity);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setBackground(Ui.roundRect(activity, Ui.PINK, 16f));
        bar.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 14),
                Ui.dp(activity, 18), Ui.dp(activity, 14));
        TextView title = Ui.bold(activity, "下载视频", 16f, Color.WHITE);
        title.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(title);
        TextView close = Ui.text(activity, "取消", 13f, Color.WHITE);
        close.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 6),
                Ui.dp(activity, 12), Ui.dp(activity, 6));
        close.setBackground(Ui.roundRect(activity, 0x33FFFFFF, 20f));
        Ui.clickable(close, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        bar.addView(close);
        root.addView(bar);

        final LinearLayout content = Ui.column(activity);
        content.setPadding(0, 0, 0, Ui.dp(activity, 12));
        content.addView(Ui.sectionTitle(activity, "正在读取可用清晰度…"));

        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 380)));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        dialog.setContentView(root);
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface ignored) {
                Window window = dialog.getWindow();
                if (window == null) {
                    return;
                }
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final BiliApi.Meta meta = BiliApi.meta(activity, ids.aid, ids.bvid);
                    if (ids.cid > 0) {
                        meta.cid = ids.cid;
                    }
                    if (meta.cid == 0) {
                        throw new Exception("拿不到 cid，可能不是单个视频页");
                    }
                    BiliApi.Dash dash = null;
                    try {
                        dash = BiliApi.dash(activity, meta.aid, meta.cid);
                    } catch (Throwable t) {
                        XLog.w("DASH 不可用：" + t);
                    }
                    final BiliApi.Dash finalDash = dash;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            render(activity, dialog, content, meta, finalDash);
                        }
                    });
                } catch (Throwable t) {
                    XLog.e("读取清晰度失败", t);
                    final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            content.removeAllViews();
                            content.addView(Ui.sectionTitle(activity, "读取失败：" + msg));
                            content.addView(Ui.sectionTitle(activity, "换个视频，或在播放页里重试"));
                        }
                    });
                }
            }
        }).start();
    }

    private static void render(final Activity activity, final Dialog dialog,
                               final LinearLayout content, final BiliApi.Meta meta,
                               final BiliApi.Dash dash) {
        content.removeAllViews();

        String shown = meta.title == null || meta.title.length() == 0 ? meta.bvid : meta.title;
        if (meta.part != null && meta.part.length() > 0) {
            shown = shown + " · " + meta.part;
        }
        TextView titleView = Ui.text(activity, shown, 12.5f, Ui.TEXT_SUB);
        titleView.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 12),
                Ui.dp(activity, 18), Ui.dp(activity, 6));
        titleView.setSingleLine(true);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(titleView);

        content.addView(Ui.sectionTitle(activity, "音画合一 MP4（推荐）"));
        LinearLayout card = Ui.card(activity);
        card.addView(row(activity, "一键下载 MP4", "最高 720P · 下载完直接能播，不用合并",
                new Runnable() {
                    @Override
                    public void run() {
                        dialog.dismiss();
                        startFastMp4(activity, meta);
                    }
                }));
        addCard(content, card);

        if (dash == null) {
            content.addView(Ui.sectionTitle(activity, "高清（DASH）"));
            content.addView(Ui.sectionTitle(activity, "这个视频没有提供 DASH 流"));
            return;
        }

        content.addView(Ui.sectionTitle(activity, "高清（DASH · 音视频分离）"));
        LinearLayout dashCard = Ui.card(activity);
        final BiliApi.Stream audio = dash.bestAudio();
        List<Integer> seen = new ArrayList<>();
        for (final BiliApi.Stream stream : dash.videos) {
            if (seen.contains(stream.qn)) {
                continue;
            }
            seen.add(stream.qn);
            dashCard.addView(row(activity, stream.label, stream.detail(), new Runnable() {
                @Override
                public void run() {
                    dialog.dismiss();
                    startDash(activity, meta, stream, audio);
                }
            }));
            dashCard.addView(Ui.divider(activity));
        }
        addCard(content, dashCard);

        TextView tip = Ui.text(activity,
                "分离的两个文件（.m4s）要用 ffmpeg 之类的工具合并：\n"
                        + "ffmpeg -i video.m4s -i audio.m4s -c copy out.mp4\n"
                        + (BiliApi.loggedIn() ? "已检测到登录态。" : "未检测到登录态，高清可能只给到 480P/720P。"),
                11f, Ui.TEXT_SUB);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.leftMargin = Ui.dp(activity, 22);
        tipLp.rightMargin = Ui.dp(activity, 22);
        tipLp.topMargin = Ui.dp(activity, 10);
        tip.setLayoutParams(tipLp);
        content.addView(tip);
    }

    private static View row(Activity activity, String title, String subtitle, final Runnable onClick) {
        LinearLayout row = Ui.item(activity, title, subtitle, null);
        Ui.clickable(row, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onClick.run();
            }
        });
        return row;
    }

    private static void addCard(LinearLayout content, View card) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int margin = Ui.dp(card.getContext(), 16);
        lp.leftMargin = margin;
        lp.rightMargin = margin;
        card.setLayoutParams(lp);
        content.addView(card);
    }

    // ---------------------------------------------------------------- 下载编排

    private static void startFastMp4(final Activity activity, final BiliApi.Meta meta) {
        final ProgressUi progress = ProgressUi.show(activity, "正在获取地址…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final BiliApi.FastMp4 mp4 = BiliApi.fastMp4(activity, meta.aid, meta.cid);
                    String base = Downloader.safeName(meta.title
                            + (meta.part != null && meta.part.length() > 0 ? "_" + meta.part : ""));
                    final String name = base + ".mp4";
                    progress.setTitle("下载中 · " + BiliApi.qualityName(mp4.quality));
                    Downloader.enqueue(activity, mp4.url, name, "video/mp4",
                            progress.callback(activity));
                } catch (Throwable t) {
                    XLog.e("取 MP4 地址失败", t);
                    final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            progress.close();
                            toast(activity, "失败：" + msg);
                        }
                    });
                }
            }
        }).start();
    }

    private static void startDash(final Activity activity, final BiliApi.Meta meta,
                                  final BiliApi.Stream video, final BiliApi.Stream audio) {
        final ProgressUi progress = ProgressUi.show(activity, "下载中 · " + video.label);
        String base = Downloader.safeName(meta.title
                + (meta.part != null && meta.part.length() > 0 ? "_" + meta.part : ""));
        final String videoName = base + "_" + video.label + "_video.m4s";
        final String audioName = base + "_audio.m4s";
        final long videoEstimate = video.bandwidth > 0 ? video.bandwidth / 8 * 60 : 0;
        final long audioEstimate = audio != null && audio.bandwidth > 0 ? audio.bandwidth / 8 * 60 : 0;
        final long totalEstimate = Math.max(1, videoEstimate + audioEstimate);

        Downloader.enqueue(activity, video.url, videoName, "video/iso.segment",
                new Downloader.Callback() {
                    @Override
                    public void onProgress(long received, long total) {
                        if (videoEstimate > 0) {
                            progress.setFraction(received * 100 / totalEstimate);
                        } else if (total > 0) {
                            progress.setFraction(received * 50 / total);
                        }
                    }

                    @Override
                    public void onDone(String savedTo) {
                        XLog.i("视频流完成：" + savedTo);
                        if (audio == null || audio.url == null) {
                            progress.close();
                            toast(activity, "已保存：" + savedTo);
                            return;
                        }
                        progress.setTitle("下载中 · 音频流");
                        Downloader.enqueue(activity, audio.url, audioName, "audio/mp4",
                                new Downloader.Callback() {
                                    @Override
                                    public void onProgress(long received, long total) {
                                        if (audioEstimate > 0) {
                                            progress.setFraction(50 + received * 50 / audioEstimate);
                                        }
                                    }

                                    @Override
                                    public void onDone(String saved) {
                                        progress.close();
                                        toast(activity, "两个文件都下好了，在 " + Prefs.folder()
                                                + "，用 ffmpeg 合并即可");
                                    }

                                    @Override
                                    public void onError(String message) {
                                        progress.close();
                                        toast(activity, "音频下载失败：" + message);
                                    }
                                });
                    }

                    @Override
                    public void onError(String message) {
                        progress.close();
                        toast(activity, "视频下载失败：" + message);
                    }
                });
    }

    // ---------------------------------------------------------------- 进度窗口

    /** 一个不能取消的小进度框：宿主自己的 ProgressBar + 我们的文案。 */
    private static final class ProgressUi {

        private final Dialog dialog;
        private final TextView title;
        private final TextView detail;
        private final ProgressBar bar;

        private ProgressUi(Activity activity) {
            dialog = new Dialog(activity);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setCancelable(false);

            LinearLayout root = Ui.column(activity);
            root.setBackground(Ui.roundRect(activity, 0xFF23232B, 14f));
            root.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 16),
                    Ui.dp(activity, 18), Ui.dp(activity, 16));
            root.setMinimumWidth(Ui.dp(activity, 240));

            title = Ui.bold(activity, "", 14f, Color.WHITE);
            root.addView(title);

            bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 6));
            blp.topMargin = Ui.dp(activity, 12);
            bar.setLayoutParams(blp);
            root.addView(bar);

            detail = Ui.text(activity, "", 11.5f, 0xFFAAAAAA);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            dlp.topMargin = Ui.dp(activity, 8);
            detail.setLayoutParams(dlp);
            root.addView(detail);

            dialog.setContentView(root);
            dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
                @Override
                public void onShow(android.content.DialogInterface ignored) {
                    Window window = dialog.getWindow();
                    if (window == null) {
                        return;
                    }
                    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
            });
            dialog.show();
        }

        static ProgressUi show(Activity activity, String text) {
            ProgressUi ui = new ProgressUi(activity);
            ui.setTitle(text);
            return ui;
        }

        void setTitle(final String text) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    title.setText(text);
                }
            });
        }

        void setFraction(final long percent) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    int p = (int) Math.max(0, Math.min(100, percent));
                    bar.setProgress(p);
                    detail.setText(p + "%");
                }
            });
        }

        void close() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                    } catch (Throwable ignored) {
                        // 页面已经没了
                    }
                }
            });
        }

        Downloader.Callback callback(final Activity activity) {
            return new Downloader.Callback() {
                @Override
                public void onProgress(long received, long total) {
                    if (total > 0) {
                        setFraction(received * 100 / total);
                    }
                    setDetail(Downloader.humanSize(received)
                            + (total > 0 ? " / " + Downloader.humanSize(total) : ""));
                }

                @Override
                public void onDone(String savedTo) {
                    close();
                    toast(activity, "已保存：" + savedTo);
                    XLog.i("下载完成：" + savedTo);
                }

                @Override
                public void onError(String message) {
                    close();
                    toast(activity, "下载失败：" + message);
                }
            };
        }

        private void setDetail(final String text) {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    detail.setText(text);
                }
            });
        }
    }

    private static void toast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 没有窗口就算了
        }
    }
}
