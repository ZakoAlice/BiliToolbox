package io.github.zakoalice.bilitoolbox;

import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * hook 的统一入口，主要解决一个坑：
 *
 * <p>{@code XposedBridge.hookAllMethods(cls, name, cb)} 只 hook **该类自己声明**的方法。
 * 如果 {@code HomeUserCenterFragment} 没有重写 {@code onResume}（只是继承），那我们按类名
 * 挂上去会一个方法都挂不到，而且不会报错 —— 功能就这么静默失效了。
 *
 * <p>所以这里在「自己没声明」时，退到父类链上再挂，回调由调用方用 isInstance 过滤。
 */
public final class Hooks {

    private Hooks() {
    }

    /** 只 hook 本类声明的方法，返回挂上了几个。 */
    public static int hookDeclared(Class<?> cls, String method, XC_MethodHook callback) {
        if (cls == null) {
            return 0;
        }
        try {
            Set<XC_MethodHook.Unhook> set = XposedBridge.hookAllMethods(cls, method, callback);
            return set == null ? 0 : set.size();
        } catch (Throwable t) {
            XLog.w("hook " + cls.getName() + "#" + method + " 失败：" + t);
            return 0;
        }
    }

    /**
     * 先挂本类；本类没声明这个方法就逐级往父类找。
     *
     * @param parentCandidates 父类候选（按从近到远的顺序），找不到就跳过
     * @return 是否至少挂上一个
     */
    public static boolean hookWithParents(Class<?> cls, ClassLoader cl, String method,
                                          String[] parentCandidates, XC_MethodHook callback) {
        if (hookDeclared(cls, method, callback) > 0) {
            XLog.i("hook " + cls.getSimpleName() + "#" + method);
            return true;
        }
        for (String name : parentCandidates) {
            Class<?> parent = Reflect.findClass(name, cl);
            if (parent == null) {
                continue;
            }
            if (hookDeclared(parent, method, callback) > 0) {
                XLog.i("hook " + name + "#" + method + "（父类兜底）");
                return true;
            }
        }
        XLog.w("hook 失败：" + cls.getName() + "#" + method + " 在自身和父类里都没找到");
        return false;
    }

    public static final String[] FRAGMENT_PARENTS = new String[]{
            "androidx.fragment.app.Fragment",
            "android.app.Fragment",
    };

    public static final String[] ACTIVITY_PARENTS = new String[]{
            "android.app.Activity",
            "androidx.appcompat.app.AppCompatActivity",
            "androidx.fragment.app.FragmentActivity",
    };
}
