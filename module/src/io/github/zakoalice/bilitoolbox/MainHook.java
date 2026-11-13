package io.github.zakoalice.bilitoolbox;

import android.app.Application;
import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LSPosed 入口。
 *
 * <p>只在哔哩哔哩（手机版 / HD）的主进程里干活。拿 Context 的方式是 hook
 * {@code Application.attach(Context)} —— 这是最早能拿到宿主 Context 且 classLoader 已经
 * 能加载宿主类的位置，比在 handleLoadPackage 里直接找类安全得多。
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String PKG_PHONE = "tv.danmaku.bili";
    private static final String PKG_HD = "tv.danmaku.bilibilihd";

    private static volatile boolean installed;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        if (!PKG_PHONE.equals(pkg) && !PKG_HD.equals(pkg)) {
            return;
        }
        if (!lpparam.isFirstApplication) {
            // :web / :push 之类的子进程不需要按钮和菜单
            return;
        }

        XLog.i("attach " + pkg + " process=" + lpparam.processName);

        try {
            XposedBridge.hookAllMethods(Application.class, "attach", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (installed) {
                        return;
                    }
                    if (param.args == null || param.args.length == 0
                            || !(param.args[0] instanceof Context)) {
                        return;
                    }
                    installed = true;
                    try {
                        BiliToolbox.install((Context) param.args[0], lpparam.classLoader, pkg);
                    } catch (Throwable t) {
                        XLog.e("初始化失败", t);
                    }
                }
            });
        } catch (Throwable t) {
            XLog.e("hook Application.attach 失败", t);
        }
    }
}
