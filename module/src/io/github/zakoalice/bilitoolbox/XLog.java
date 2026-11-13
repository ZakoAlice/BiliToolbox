package io.github.zakoalice.bilitoolbox;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.XposedBridge;

/**
 * 统一日志出口。
 *
 * <p>三个去向：LSPosed 模块日志、logcat、进程内环形缓冲。最后一个是给设置面板的
 * 「运行日志」用的 —— 普通用户不会去开 adb，出了问题能直接在 App 里看到发生了什么。
 *
 * <p>所有方法都不会抛出异常：日志本身不该成为崩溃源。
 */
public final class XLog {

    public static final String TAG = "BiliToolbox";

    private static final int MAX_LINES = 300;
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private XLog() {
    }

    public static void i(String message) {
        write("I", message, null);
    }

    public static void w(String message) {
        write("W", message, null);
    }

    public static void e(String message) {
        write("E", message, null);
    }

    public static void e(String message, Throwable t) {
        write("E", message + " :: " + t, null);
    }

    private static void write(String level, String message, Throwable t) {
        String line = TIME.format(new Date()) + " " + level + "/" + message;
        try {
            Log.println(level.equals("E") ? Log.ERROR : level.equals("W") ? Log.WARN : Log.INFO, TAG, message);
        } catch (Throwable ignored) {
            // 宿主被裁剪过的 Log 也不该影响主流程
        }
        try {
            XposedBridge.log(TAG + ": " + message);
        } catch (Throwable ignored) {
            // 非 Xposed 环境（例如单元测试）忽略
        }
        synchronized (BUFFER) {
            while (BUFFER.size() >= MAX_LINES) {
                BUFFER.removeFirst();
            }
            BUFFER.addLast(line);
        }
    }

    /** 最近若干行日志，新的在后面。 */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        synchronized (BUFFER) {
            for (String line : BUFFER) {
                sb.append(line).append('\n');
            }
        }
        if (sb.length() == 0) {
            sb.append("（暂无日志）");
        }
        return sb.toString();
    }
}
