package io.github.zakoalice.bilitoolbox.share;

import android.app.Activity;
import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.ref.WeakReference;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.zakoalice.bilitoolbox.Activities;
import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.Reflect;
import io.github.zakoalice.bilitoolbox.XLog;

/**
 * 在 {@code SuperMenu} 这一层往分享面板里加一项。
 *
 * <p>为什么换到这里：B站的分享面板实现换过好几套 —— 9.17 有 {@code supermenu.share.v2.r}
 * （异步拉渠道）、{@code supermenu.share.v2.i}（用现成数据直接画）、{@code supermenu.share.r}
 * （老版）。它们的构建方法名、参数、是不是 static 全不一样，逐个别名去 hook 必然漏。
 *
 * <p>但它们最后都要落到同一个类上：{@code com.bilibili.app.comm.supermenu.SuperMenu}
 * （全包 34 个入口都调 {@code SuperMenu.with()}），而菜单项列表只从这里进：
 * <pre>
 *   SuperMenu.addMenus(List&lt;IMenu&gt;)   // 面板画之前，先把它塞进来
 *   SuperMenu.itemClickListener(listener) // 点击监听也注册在这里
 * </pre>
 * 所以在这两个方法上各 hook 一次，就把「谁画的面板」这个问题彻底绕过去了。
 *
 * <p>点击的处理更狠一点：面板把宿主自己的监听器注册进来后，我们把它换成一个动态代理 ——
 * 点我们那一项就自己接住，其它项原样转交给宿主的监听器。这样不管宿主后续怎么分发，都绕不过去。
 */
public final class SuperMenuEntry {

    private static final String SUPER_MENU = "com.bilibili.app.comm.supermenu.SuperMenu";
    private static final String LISTENER =
            "com.bilibili.app.comm.supermenu.core.listeners.OnMenuItemClickListenerV2";
    private static final String MENU_ITEM = "com.bilibili.app.comm.supermenu.core.MenuItemImpl";
    private static final String ICON_HELPER =
            "com.bilibili.app.comm.supermenu.share.ShareChannelHelper";
    private static final String ITEM_HANDLER =
            "com.bilibili.app.comm.supermenu.share.v2.MenuItemHandler";

    private static final String TITLE = ShareEntry.TITLE;
    private static final String MARK = ShareEntry.MARK;

    /** 宿主里下载箭头那张图的资源 id，安装时先问一次。 */
    private static volatile int iconRes;
    private static volatile Object ourItem;
    private static volatile Object listenerProxy;
    private static volatile ClassLoader loader;
    private static volatile boolean injectedOnce;
    private static volatile boolean clickReady;

    private SuperMenuEntry() {
    }

    public static void install(ClassLoader cl) {
        loader = cl;
        Class<?> superMenu = Reflect.findClass(SUPER_MENU, cl);
        if (superMenu == null) {
            XLog.w("分享入口：找不到 " + SUPER_MENU + "，菜单层注入放弃");
            return;
        }
        iconRes = queryDownloadIcon(cl);
        hookAddMenus(superMenu);
        hookAddMenu(superMenu);
        hookAttach(superMenu);
        hookListener(superMenu, cl);
        hookItemHandler(cl);
    }

    /**
     * 面板挂载的那一刻再补一次。
     *
     * <p>有的实现会在 {@code attach} 里把菜单列表复制给面板，光靠 addMenus 之后的注入
     * 可能赶在复制之前或之后，两头都试一遍最保险（重复注入会被标题去重挡掉）。
     */
    private static void hookAttach(Class<?> superMenu) {
        int hooked = 0;
        for (Method m : superMenu.getDeclaredMethods()) {
            if (!"attach".equals(m.getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object menus = Reflect.call(param.thisObject, "getMenus");
                            if (menus != null) {
                                inject(param.thisObject, menus, "attach");
                            }
                        } catch (Throwable t) {
                            XLog.w("分享菜单注入失败（attach）：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 另一个重载
            }
        }
        if (hooked > 0) {
            XLog.i("分享面板 attach 已挂钩 " + hooked + " 个");
        }
    }

    // ------------------------------------------------------------------ 注入

    private static void hookAddMenus(Class<?> superMenu) {
        Method addMenus = null;
        for (Method m : superMenu.getDeclaredMethods()) {
            if ("addMenus".equals(m.getName()) && m.getParameterTypes().length == 1) {
                addMenus = m;
                break;
            }
        }
        if (addMenus == null) {
            XLog.w("分享入口：SuperMenu 没有 addMenus(List)");
            return;
        }
        try {
            XposedBridge.hookMethod(addMenus, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) {
                            return;
                        }
                        inject(param.thisObject, param.args[0], "addMenus");
                    } catch (Throwable t) {
                        XLog.w("分享菜单注入失败：" + t);
                    }
                }
            });
            XLog.i("分享菜单已挂钩 SuperMenu#addMenus");
        } catch (Throwable t) {
            XLog.e("hook SuperMenu#addMenus 失败", t);
        }
    }

    /** 有的面板是「一条一条 addMenu」的，这里也接一手。 */
    private static void hookAddMenu(Class<?> superMenu) {
        Method addMenu = null;
        for (Method m : superMenu.getDeclaredMethods()) {
            if (!"addMenu".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            if (m.getParameterTypes()[0].getName().endsWith("IMenu")) {
                addMenu = m;
                break;
            }
        }
        if (addMenu == null) {
            return;
        }
        try {
            XposedBridge.hookMethod(addMenu, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args == null || param.args.length == 0) {
                            return;
                        }
                        Object menus = Reflect.call(param.thisObject, "getMenus");
                        if (menus == null) {
                            return;
                        }
                        inject(param.thisObject, menus, "addMenu");
                    } catch (Throwable t) {
                        XLog.w("分享菜单注入失败（addMenu）：" + t);
                    }
                }
            });
        } catch (Throwable ignored) {
            // addMenus 那条已经够用
        }
    }

    @SuppressWarnings("unchecked")
    private static void inject(Object superMenu, Object menusObj, String from) {
        if (!Prefs.shareEntryEnabled()) {
            return;
        }
        if (!(menusObj instanceof List)) {
            return;
        }
        List<Object> menus = (List<Object>) menusObj;
        if (menus.isEmpty()) {
            return;
        }
        Context context = contextOf(superMenu);
        if (context == null) {
            XLog.w("分享菜单注入：拿不到 Context，跳过");
            return;
        }
        Object target = pickMenu(menus);
        if (target == null) {
            XLog.w("分享菜单注入：没有可挂的菜单");
            return;
        }
        Object items = Reflect.call(target, "getMenuItems");
        if (items instanceof List && containsOurs((List<Object>) items)) {
            return;
        }
        if (!injectedOnce && items instanceof List) {
            // 诊断用：把面板里现有的项目名打出来，换版本时一眼就知道注入到了哪儿
            XLog.i("分享面板现有 " + ((List<?>) items).size() + " 项：" + titles((List<Object>) items));
        }
        Object item = buildItem(context);
        if (item == null) {
            return;
        }
        Object added = Reflect.call(target, "addMenuItem", item);
        ourItem = item;
        if (!injectedOnce) {
            injectedOnce = true;
            XLog.i("分享渠道已注入：" + TITLE + "（经 SuperMenu#" + from + "）");
        }
        if (added == null) {
            XLog.w("addMenuItem 没有返回，面板里可能看不到（共 " + menus.size() + " 组菜单）");
        }
    }

    /** 优先挂到「渠道那一排」（isBelowChannel），没有就挂最后一组。 */
    private static Object pickMenu(List<Object> menus) {
        for (Object menu : menus) {
            Object below = Reflect.call(menu, "isBelowChannel");
            if (Boolean.TRUE.equals(below)) {
                return menu;
            }
        }
        return menus.get(menus.size() - 1);
    }

    /** 把菜单项标题拼成一行，只用于日志。 */
    private static String titles(List<Object> items) {
        StringBuilder sb = new StringBuilder();
        for (Object item : items) {
            Object title = Reflect.call(item, "getTitle");
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(title == null ? "?" : title.toString());
        }
        return sb.toString();
    }

    private static boolean containsOurs(List<Object> items) {        for (Object item : items) {
            if (item == null) {
                continue;
            }
            Object title = Reflect.call(item, "getTitle");
            if (title != null && TITLE.contentEquals(title.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 造一个宿主自己的菜单项。
     *
     * <p>构造签名是从 {@code ShareMenuBuilder#forMenuItem} 里抄来的：
     * {@code MenuItemImpl(Context, String shareWay, int iconRes, CharSequence title)}。
     * 用它而不是 {@code forMenuItem("SYS_DOWNLOAD")}，是因为那个方法只认
     * WEIXIN/QQ/SINA/COPY 那几个平台名，SYS_DOWNLOAD 会返回 null。
     */
    private static Object buildItem(Context context) {
        if (iconRes == 0) {
            iconRes = queryDownloadIcon(loader);
        }
        Class<?> type = Reflect.findClass(MENU_ITEM, loader);
        if (type == null) {
            XLog.w("分享入口：找不到 " + MENU_ITEM);
            return null;
        }
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] ps = ctor.getParameterTypes();
            if (ps.length != 4 || ps[1] != String.class || ps[2] != int.class
                    || !CharSequence.class.isAssignableFrom(ps[3])) {
                continue;
            }
            try {
                ctor.setAccessible(true);
                Object item = ctor.newInstance(context, MARK, iconRes, TITLE);
                // 被 R8 裁掉的默认值有可能是 false，显式打开
                Reflect.call(item, "setVisible", Boolean.TRUE);
                Reflect.call(item, "setShareWay", MARK);
                return item;
            } catch (Throwable t) {
                XLog.w("构造分享菜单项失败：" + t);
            }
        }
        XLog.w("分享入口：MenuIteImpl 没有 (Context, String, int, CharSequence) 构造");
        return null;
    }

    /** 问宿主要「下载」那张图的资源 id（SYS_DOWNLOAD 在图标表里就是下载箭头）。 */
    private static int queryDownloadIcon(ClassLoader cl) {
        Class<?> helper = Reflect.findClass(ICON_HELPER, cl);
        if (helper == null) {
            return 0;
        }
        try {
            Method get = helper.getDeclaredMethod("getDefaultIconRes", String.class);
            get.setAccessible(true);
            Object res = get.invoke(null, "SYS_DOWNLOAD");
            if (res instanceof Integer) {
                return (Integer) res;
            }
        } catch (Throwable t) {
            XLog.w("取下载图标失败：" + t);
        }
        return 0;
    }

    private static Context contextOf(Object superMenu) {
        Object ref = Reflect.field(superMenu, "a");
        if (ref instanceof WeakReference) {
            Object value = ((WeakReference<?>) ref).get();
            if (value instanceof Context) {
                return (Context) value;
            }
        }
        Activity activity = Activities.top();
        return activity;
    }

    // ------------------------------------------------------------------ 点击

    private static void hookListener(Class<?> superMenu, ClassLoader cl) {
        final Class<?> iface = Reflect.findClass(LISTENER, cl);
        if (iface == null) {
            XLog.w("分享入口：找不到 " + LISTENER + "，点击只能靠兜底");
            return;
        }
        int hooked = 0;
        for (Method m : superMenu.getDeclaredMethods()) {
            if (!"itemClickListener".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            wrapListener(param.thisObject, iface);
                        } catch (Throwable t) {
                            XLog.w("包装点击监听失败：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 换一个重载
            }
        }
        if (hooked > 0) {
            XLog.i("分享点击监听已挂钩 " + hooked + " 个 itemClickListener");
        } else {
            XLog.w("分享入口：SuperMenu 没有 itemClickListener，点击走兜底");
        }
    }

    /**
     * 把宿主注册进来的监听器换成动态代理。
     *
     * <p>字段名被混淆成单字母，所以按「字段类型 == 监听接口」来找，不认名字。
     */
    private static void wrapListener(Object superMenu, Class<?> iface) {
        if (superMenu == null) {
            return;
        }
        Field target = null;
        Object current = null;
        for (Field f : Reflect.fields(superMenu.getClass())) {
            if (!f.getType().equals(iface)) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(superMenu);
                if (value == null) {
                    continue;
                }
                target = f;
                current = value;
                break;
            } catch (Throwable ignored) {
                // 继续找
            }
        }
        if (target == null || current == null) {
            return;
        }
        if (listenerProxy != null && current == listenerProxy) {
            return;
        }
        final Object original = current;
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{iface}, new InvocationHandler() {
            @Override
            public Object invoke(Object p, Method method, Object[] args) throws Throwable {
                if ("onItemClick".equals(method.getName()) && args != null && args.length == 1
                        && isOurs(args[0])) {
                    ShareEntry.fire();
                    return Boolean.TRUE;
                }
                if ("equals".equals(method.getName()) && args != null && args.length == 1) {
                    return p == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(p);
                }
                if ("toString".equals(method.getName())) {
                    return "BiliToolboxClickListener";
                }
                try {
                    return method.invoke(original, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            }
        });
        listenerProxy = proxy;
        try {
            target.set(superMenu, proxy);
            if (!clickReady) {
                clickReady = true;
                XLog.i("分享点击已接管（代理 " + iface.getSimpleName() + "）");
            }
        } catch (Throwable t) {
            XLog.w("写回点击监听失败：" + t);
        }
    }

    /** v2 面板的点击会先经过这个统一处理器，顺手也接一手（双保险）。 */
    private static void hookItemHandler(ClassLoader cl) {
        Class<?> handler = Reflect.findClass(ITEM_HANDLER, cl);
        if (handler == null) {
            return;
        }
        for (Method m : handler.getDeclaredMethods()) {
            if (!"handleClick".equals(m.getName()) || m.getParameterTypes().length != 1) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null || param.args.length == 0) {
                                return;
                            }
                            if (isOurs(param.args[0])) {
                                param.setResult(Boolean.TRUE);
                                ShareEntry.fire();
                            }
                        } catch (Throwable t) {
                            XLog.w("分享项点击判定失败：" + t);
                        }
                    }
                });
            } catch (Throwable ignored) {
                // 有代理那层兜着
            }
        }
    }

    private static boolean isOurs(Object item) {
        if (item == null) {
            return false;
        }
        if (ourItem != null && item == ourItem) {
            return true;
        }
        Object way = Reflect.call(item, "getShareWay");
        if (MARK.equals(way)) {
            return true;
        }
        Object title = Reflect.call(item, "getTitle");
        return title != null && TITLE.contentEquals(title.toString());
    }
}
