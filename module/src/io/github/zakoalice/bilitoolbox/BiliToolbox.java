package io.github.zakoalice.bilitoolbox;

import android.content.Context;

import io.github.zakoalice.bilitoolbox.download.VideoDownloader;
import io.github.zakoalice.bilitoolbox.share.ShareEntry;
import io.github.zakoalice.bilitoolbox.ui.MineEntry;
import io.github.zakoalice.bilitoolbox.ui.SettingsDialog;

/**
 * 模块的装配点。
 *
 * <p>以后加新功能就在 {@link #install} 里多一行 —— 每个功能自己管好自己的 hook 和开关，
 * 互不依赖，任何一块失败都只影响自己那一块。
 */
public final class BiliToolbox {

    private BiliToolbox() {
    }

    public static void install(Context context, ClassLoader classLoader, String packageName) {
        Prefs.init(context);
        XLog.i("小睦工具箱 v" + SettingsDialog.VERSION + " 启动，宿主 " + packageName
                + " " + hostVersion(context, packageName));

        // 先记页面栈：分享点击要靠它找到「用户正在看的页面」，晚一步就漏掉当前这个
        Activities.track();

        // 功能 1：我的页入口（设置面板）
        try {
            MineEntry.install(classLoader, packageName);
        } catch (Throwable t) {
            XLog.e("我的页入口装配失败", t);
        }

        // 功能 2：分享面板里的「下载视频」
        try {
            ShareEntry.install(classLoader);
        } catch (Throwable t) {
            XLog.e("分享入口装配失败", t);
        }

        // 下载器本身没有被 hook 的入口，由分享项回调过来
        XLog.i(VideoDownloader.class.getSimpleName() + " ready");
    }

    /** 打印宿主版本：适配新版本时第一眼要看的就是它。 */
    private static String hostVersion(Context context, String packageName) {
        try {
            android.content.pm.PackageInfo info =
                    context.getPackageManager().getPackageInfo(packageName, 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return info.versionName + " (" + code + ")";
        } catch (Throwable t) {
            return "版本未知";
        }
    }
}
