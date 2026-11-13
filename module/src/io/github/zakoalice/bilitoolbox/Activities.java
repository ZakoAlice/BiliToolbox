package io.github.zakoalice.bilitoolbox;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * 找当前 Activity。
 *
 * <p>分享面板的回调里只能拿到一些领域对象，拿不到 Context；而弹 Dialog 必须有窗口 token。
 * 找错页面的后果很直观：Dialog 挂到了后台页面（首页）上，屏幕上什么都不显示，
 * 等你返回首页时它才冒出来 —— 所以这里必须拿到「用户此刻正在看的那个页面」。
 *
 * <p>两条路，按可靠性排序：
 * <ol>
 *   <li><b>自己记的 resume 栈</b>（{@link #track()}）：Activity.onResume 时入栈、onPause 时出栈。
 *       顺序确定，谁在最上面一目了然。</li>
 *   <li><b>ActivityThread.mActivities</b>：拿不到栈时的兜底。注意它是 HashMap，
 *       <b>遍历顺序没有任何保证</b> —— 之前就是取「最后遍历到的那个」，多开一个页面就开始抽盲盒，
 *       这才是「在首页弹出」的根因。所以这里必须按 {@code record.paused} 挑没暂停的那个。</li>
 * </ol>
 */
public final class Activities {

    /** 最近被 resume 的页面，最后进的在末尾；onPause / onDestroy 时移除。 */
    private static final List<Activity> RESUMED = new ArrayList<>();

    /** 栈最多留这么多层，防止个别页面漏了 onPause 一直堆着。 */
    private static final int MAX_TRACKED = 12;

    private static volatile boolean tracked;
    private static volatile boolean trackFailed;

    private Activities() {
    }

    /**
     * 开始跟踪页面生命周期。必须在宿主第一个页面起来之前调用（见 {@code BiliToolbox.install}）。
     *
     * <p>hook 的是 {@code android.app.Activity} 自己的 onResume/onPause/onDestroy：
     * 宿主和 AppCompat 的子类都会 {@code super.onResume()}，所以不用关心它们叫什么名字。
     */
    public static void track() {
        if (tracked) {
            return;
        }
        tracked = true;
        try {
            XposedBridge.hookAllMethods(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) {
                        return;
                    }
                    Activity activity = (Activity) param.thisObject;
                    synchronized (RESUMED) {
                        RESUMED.remove(activity);
                        RESUMED.add(activity);
                        while (RESUMED.size() > MAX_TRACKED) {
                            RESUMED.remove(0);
                        }
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    synchronized (RESUMED) {
                        RESUMED.remove(param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    synchronized (RESUMED) {
                        RESUMED.remove(param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            trackFailed = true;
            XLog.w("跟踪页面生命周期失败（退回扫 ActivityThread）：" + t);
        }
    }

    public static boolean tracking() {
        return tracked && !trackFailed;
    }

    public static Activity of(Context context) {
        Context c = context;
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity) {
                return (Activity) c;
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return top();
    }

    /**
     * 当前用户正在看的页面。
     *
     * <p>不返回已经在 finish / destroy 的页面 —— 往那种页面挂 Dialog，宿主一收摊它就跟着没了，
     * 表现就是「点了没反应」。
     */
    public static Activity top() {
        Activity tracked = fromResumed();
        if (tracked != null) {
            return tracked;
        }
        return fromRecords();
    }

    /** 自己那份 resume 栈的最上层。 */
    private static Activity fromResumed() {
        synchronized (RESUMED) {
            for (int i = RESUMED.size() - 1; i >= 0; i--) {
                Activity activity = RESUMED.get(i);
                if (activity == null || activity.isFinishing()) {
                    RESUMED.remove(i);
                    continue;
                }
                return activity;
            }
        }
        return null;
    }

    /** 兜底：扫 ActivityThread.mActivities，优先挑 record.paused == false 的那个。 */
    private static Activity fromRecords() {
        try {
            Class<?> threadClass = Class.forName("android.app.ActivityThread");
            Object thread = threadClass.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Object map = Reflect.field(thread, "mActivities");
            if (!(map instanceof Map)) {
                return null;
            }
            Activity fallback = null;
            for (Object record : ((Map<?, ?>) map).values()) {
                Object value = Reflect.field(record, "activity");
                if (!(value instanceof Activity)) {
                    continue;
                }
                Activity activity = (Activity) value;
                if (activity.isFinishing()) {
                    continue;
                }
                if (fallback == null) {
                    fallback = activity;
                }
                Object paused = Reflect.field(record, "paused");
                if (!Boolean.TRUE.equals(paused)) {
                    // 没暂停 = 当前展示的那一个，直接定下来
                    return activity;
                }
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }
}
