package io.github.zakoalice.bilitoolbox;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块配置。
 *
 * <p>存哪儿是个需要想一下的问题：模块自己没有常驻进程，hook 代码跑在哔哩哔哩进程里，
 * 所以直接用宿主的私有 SharedPreferences（/data/data/tv.danmaku.bili/shared_prefs/）。
 * 好处是不用额外权限、卸载模块不丢配置；代价是清 B 站数据会一起清掉 —— 可以接受。
 */
public final class Prefs {

    private static final String FILE = "bili_toolbox_prefs";

    public static final String KEY_ENABLE_DOWNLOAD = "enable_download";
    public static final String KEY_ENABLE_ENTRY = "enable_mine_entry";
    public static final String KEY_ENABLE_SHARE = "enable_share_entry";
    public static final String KEY_FAST_MP4 = "prefer_fast_mp4";
    public static final String KEY_FOLDER = "save_folder";

    public static final String DEFAULT_FOLDER = "Download/小睦工具箱";

    private static SharedPreferences sp;

    /**
     * attach 阶段拿到的 Context，留着给后面重试。
     *
     * <p>{@code Application.attach(Context)} 那一刻 {@code getApplicationContext()} 还是 null
     * —— 这层壳还没装好自己。用它去 getSharedPreferences 就是 NPE，第一次初始化必然失败。
     * 所以这里退一步用传入的 Context 本身，并把 Context 存下来，等真正读配置时再试一次。
     */
    private static Context host;

    private Prefs() {
    }

    public static synchronized void init(Context context) {
        if (sp != null || context == null) {
            return;
        }
        host = context;
        open();
    }

    private static void open() {
        Context context = host;
        if (context == null) {
            return;
        }
        Context app = null;
        try {
            app = context.getApplicationContext();
        } catch (Throwable ignored) {
            // 同上，attach 阶段可能直接抛
        }
        if (app == null) {
            app = context;
        }
        try {
            sp = app.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        } catch (Throwable t) {
            XLog.e("打开配置失败，改用内存配置", t);
            sp = null;
        }
    }

    private static SharedPreferences store() {
        if (sp == null && host != null) {
            // 第一次没成功（attach 太早），这里补一次
            open();
        }
        return sp;
    }

    public static boolean getBool(String key, boolean def) {
        SharedPreferences s = store();
        if (s == null) {
            return def;
        }
        try {
            return s.getBoolean(key, def);
        } catch (Throwable t) {
            return def;
        }
    }

    public static void setBool(String key, boolean value) {
        SharedPreferences s = store();
        if (s == null) {
            return;
        }
        try {
            s.edit().putBoolean(key, value).apply();
        } catch (Throwable t) {
            XLog.e("写配置失败 " + key, t);
        }
    }

    public static String getString(String key, String def) {
        SharedPreferences s = store();
        if (s == null) {
            return def;
        }
        try {
            String v = s.getString(key, def);
            return v == null || v.length() == 0 ? def : v;
        } catch (Throwable t) {
            return def;
        }
    }

    public static void setString(String key, String value) {
        SharedPreferences s = store();
        if (s == null) {
            return;
        }
        try {
            s.edit().putString(key, value).apply();
        } catch (Throwable t) {
            XLog.e("写配置失败 " + key, t);
        }
    }

    // ---------------------------------------------------------------- 语义封装

    public static boolean downloadEnabled() {
        return getBool(KEY_ENABLE_DOWNLOAD, true);
    }

    /** 分享面板里是否加「下载视频」这一项。 */
    public static boolean shareEntryEnabled() {
        return getBool(KEY_ENABLE_SHARE, true) && downloadEnabled();
    }

    public static boolean entryEnabled() {
        return getBool(KEY_ENABLE_ENTRY, true);
    }

    /** true = 默认走音画合一的 MP4（最高 720P），false = 走 DASH 高画质分离流。 */
    public static boolean preferFastMp4() {
        return getBool(KEY_FAST_MP4, true);
    }

    public static String folder() {
        return getString(KEY_FOLDER, DEFAULT_FOLDER);
    }
}
