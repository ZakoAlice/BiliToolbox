package io.github.zakoalice.bilitoolbox.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import io.github.zakoalice.bilitoolbox.Activities;
import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.XLog;

/**
 * 设置面板：点了「我的 → 更多服务 → 小睦工具箱」之后弹出来的东西。
 *
 * <p>用宿主 Activity 当 Context 现搭一个 Dialog —— 模块没有可用的 Activity，
 * 也绝不注册自己的 Activity（宿主进程加载不到模块的清单）。界面全部代码构建，
 * 不引用任何资源 id，避免和宿主的 R 表撞车。
 */
public final class SettingsDialog {

    public static final String VERSION = "1.8";

    private SettingsDialog() {
    }

    public static void show(Context raw) {
        final Activity activity = activityOf(raw);
        if (activity == null) {
            XLog.w("设置面板打不开：拿不到 Activity");
            toast(raw, "小睦工具箱：拿不到 Activity，请从「我的」页再点一次");
            return;
        }
        try {
            build(activity).show();
        } catch (Throwable t) {
            XLog.e("设置面板构建失败", t);
            toast(activity, "小睦工具箱：面板打开失败 " + t);
        }
    }

    private static Dialog build(final Activity activity) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = Ui.column(activity);
        root.setBackground(Ui.roundRect(activity, Ui.BG, 16f));
        root.addView(header(activity, dialog));

        ScrollView scroll = new ScrollView(activity);
        // weight=1 只有在父窗口高度确定时才有意义，所以下面 onShow 里把窗口高度钉成屏幕的 72%。
        // （之前用 WRAP_CONTENT 配 weight，高度会被算成 0，整块内容看不见。）
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(scrollLp);

        final LinearLayout content = Ui.column(activity);
        content.setPadding(0, 0, 0, Ui.dp(activity, 14));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll);

        fillContent(activity, content, dialog);
        dialog.setContentView(root);

        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface ignored) {
                Window window = dialog.getWindow();
                if (window == null) {
                    return;
                }
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f);
                int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * 0.72f);
                window.setLayout(width, height);
            }
        });
        return dialog;
    }

    // ------------------------------------------------------------------- 内容

    private static void fillContent(final Activity activity, final LinearLayout content, final Dialog dialog) {
        content.addView(Ui.sectionTitle(activity, "下载"));

        LinearLayout downloadCard = Ui.card(activity);
        downloadCard.addView(switchRow(activity, "视频下载",
                "在「分享」面板里加一项「下载视频」",
                Prefs.downloadEnabled(),
                new OnToggle() {
                    @Override
                    public void onChanged(boolean checked) {
                        Prefs.setBool(Prefs.KEY_ENABLE_DOWNLOAD, checked);
                        XLog.i("下载功能总开关 -> " + checked);
                    }
                }));
        downloadCard.addView(Ui.divider(activity));
        downloadCard.addView(switchRow(activity, "分享面板入口",
                "关掉后分享面板里不再出现下载项（下次分享生效）",
                Prefs.shareEntryEnabled(), new OnToggle() {
                    @Override
                    public void onChanged(boolean checked) {
                        Prefs.setBool(Prefs.KEY_ENABLE_SHARE, checked);
                        XLog.i("分享面板入口 -> " + checked);
                    }
                }));
        downloadCard.addView(Ui.divider(activity));
        downloadCard.addView(valueRow(activity, "下载方式",
                Prefs.preferFastMp4() ? "音画合一 MP4（最高 720P）" : "DASH 高画质（音视频分离）",
                "点一下切换", new Runnable() {
                    @Override
                    public void run() {
                        boolean next = !Prefs.preferFastMp4();
                        Prefs.setBool(Prefs.KEY_FAST_MP4, next);
                        toast(activity, next ? "已改为音画合一 MP4" : "已改为 DASH 高画质");
                        fillRefresh(activity, content, dialog);
                    }
                }));
        downloadCard.addView(Ui.divider(activity));
        downloadCard.addView(valueRow(activity, "保存位置", Prefs.folder(),
                "手机存储根目录下", null));
        addCard(content, downloadCard);

        content.addView(Ui.sectionTitle(activity, "入口"));
        LinearLayout entryCard = Ui.card(activity);
        entryCard.addView(switchRow(activity, "在「我的」页显示入口",
                "关掉后「更多服务」里不再出现小睦工具箱，重进我的页生效",
                Prefs.entryEnabled(), new OnToggle() {
                    @Override
                    public void onChanged(boolean checked) {
                        Prefs.setBool(Prefs.KEY_ENABLE_ENTRY, checked);
                        XLog.i("我的页入口开关 -> " + checked);
                    }
                }));
        addCard(content, entryCard);

        content.addView(Ui.sectionTitle(activity, "调试"));
        LinearLayout aboutCard = Ui.card(activity);
        aboutCard.addView(valueRow(activity, "运行日志", "查看最近的 hook / 下载记录", "打开",
                new Runnable() {
                    @Override
                    public void run() {
                        showLog(activity);
                    }
                }));
        aboutCard.addView(Ui.divider(activity));
        aboutCard.addView(valueRow(activity, "宿主版本", hostVersion(activity), null, null));
        aboutCard.addView(Ui.divider(activity));
        aboutCard.addView(valueRow(activity, "模块版本", "小睦工具箱 v" + VERSION + " · LSPosed", null, null));
        addCard(content, aboutCard);

        TextView footer = Ui.text(activity,
                "下载走哔哩哔哩自己的 playurl 接口。高清（1080P 及以上）需要登录态与对应权益，"
                        + "得不到时只能拿到 720P 及以下 —— 这不是模块能绕过的部分。",
                11.5f, Ui.TEXT_SUB);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fp.leftMargin = Ui.dp(activity, 22);
        fp.rightMargin = Ui.dp(activity, 22);
        fp.topMargin = Ui.dp(activity, 14);
        footer.setLayoutParams(fp);
        content.addView(footer);
    }

    /** 切换下载方式后原地刷新内容（不关窗口）。 */
    private static void fillRefresh(Activity activity, LinearLayout content, Dialog dialog) {
        content.removeAllViews();
        fillContent(activity, content, dialog);
    }

    // ------------------------------------------------------------------ 组件

    private static View header(final Activity activity, final Dialog dialog) {
        LinearLayout bar = Ui.row(activity);
        bar.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bar.setBackground(Ui.roundRect(activity, Ui.PINK, 16f));
        bar.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 16),
                Ui.dp(activity, 18), Ui.dp(activity, 16));

        LinearLayout texts = Ui.column(activity);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textLp);
        texts.addView(Ui.bold(activity, "小睦工具箱", 17f, Color.WHITE));
        TextView sub = Ui.text(activity, "哔哩哔哩多功能模块 · v" + VERSION, 11.5f, 0xCCFFFFFF);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(activity, 3);
        sub.setLayoutParams(subLp);
        texts.addView(sub);
        bar.addView(texts);

        TextView close = Ui.text(activity, "关闭", 13f, Color.WHITE);
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
        return bar;
    }

    private interface OnToggle {
        void onChanged(boolean checked);
    }

    private static View switchRow(Activity activity, String title, String subtitle,
                                  boolean checked, final OnToggle toggle) {
        final Switch sw = new Switch(activity);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean isChecked) {
                toggle.onChanged(isChecked);
            }
        });
        LinearLayout row = Ui.item(activity, title, subtitle, sw);
        row.setClickable(false);
        return row;
    }

    private static View valueRow(Activity activity, String title, String value, String hint,
                                 final Runnable onClick) {
        LinearLayout texts = Ui.column(activity);
        TextView titleView = Ui.text(activity, title, 15f, Ui.TEXT);
        texts.addView(titleView);
        if (hint != null) {
            TextView hintView = Ui.text(activity, hint, 11.5f, Ui.TEXT_SUB);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(activity, 2);
            hintView.setLayoutParams(lp);
            texts.addView(hintView);
        }

        LinearLayout row = Ui.row(activity);
        row.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 14),
                Ui.dp(activity, 16), Ui.dp(activity, 14));
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textLp);
        row.addView(texts);

        TextView valueView = Ui.text(activity, value == null ? "" : value, 13f,
                onClick != null ? Ui.PINK_DARK : Ui.TEXT_SUB);
        valueView.setMaxWidth(Ui.dp(activity, 150));
        valueView.setGravity(android.view.Gravity.END);
        valueView.setSingleLine(true);
        valueView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams valueLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        valueLp.leftMargin = Ui.dp(activity, 10);
        valueView.setLayoutParams(valueLp);
        row.addView(valueView);

        if (onClick != null) {
            Ui.clickable(row, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClick.run();
                }
            });
        }
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

    // ------------------------------------------------------------------- 日志

    private static void showLog(final Activity activity) {
        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = Ui.column(activity);
        root.setBackground(Ui.roundRect(activity, Ui.BG, 16f));

        LinearLayout bar = Ui.row(activity);
        bar.setBackground(Ui.roundRect(activity, 0xFF2B2B33, 16f));
        bar.setPadding(Ui.dp(activity, 18), Ui.dp(activity, 14),
                Ui.dp(activity, 18), Ui.dp(activity, 14));
        TextView title = Ui.bold(activity, "运行日志", 15f, Color.WHITE);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        bar.addView(title);

        TextView copy = Ui.text(activity, "复制", 12.5f, Color.WHITE);
        copy.setPadding(Ui.dp(activity, 12), Ui.dp(activity, 5),
                Ui.dp(activity, 12), Ui.dp(activity, 5));
        copy.setBackground(Ui.roundRect(activity, 0x33FFFFFF, 20f));
        Ui.clickable(copy, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyLog(activity);
            }
        });
        bar.addView(copy);
        root.addView(bar);

        TextView body = Ui.text(activity, XLog.dump(), 11f, 0xFFD8DEE9);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextIsSelectable(true);
        body.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 12),
                Ui.dp(activity, 14), Ui.dp(activity, 14));

        ScrollView scroll = new ScrollView(activity);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(activity, 380));
        scroll.setLayoutParams(slp);
        scroll.setBackgroundColor(0xFF1B1B22);
        scroll.addView(body);
        root.addView(scroll);

        dialog.setContentView(root);
        dialog.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(DialogInterface ignored) {
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
    }

    private static void copyLog(Activity activity) {
        try {
            Object clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard instanceof android.content.ClipboardManager) {
                ((android.content.ClipboardManager) clipboard)
                        .setPrimaryClip(android.content.ClipData.newPlainText("BiliToolbox log", XLog.dump()));
                toast(activity, "日志已复制到剪贴板");
            }
        } catch (Throwable t) {
            XLog.e("复制日志失败", t);
        }
    }

    // ------------------------------------------------------------------ 杂项

    private static String hostVersion(Activity activity) {
        try {
            PackageManager pm = activity.getPackageManager();
            String name = pm.getPackageInfo(activity.getPackageName(), 0).versionName;
            return activity.getPackageName() + " " + name;
        } catch (Throwable t) {
            return "未知";
        }
    }

    /** 从任意 Context 里掏出 Activity —— Dialog 必须有窗口 token。 */
    public static Activity activityOf(Context context) {
        return Activities.of(context);
    }

    private static void toast(Context context, String message) {
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
            // 没有 UI 线程就算了
        }
    }

    static {
        if (Build.VERSION.SDK_INT < 21) {
            // 占位，保持 Build 引用（minSdk 24，实际上不会进来）
            XLog.i("old sdk");
        }
    }
}
