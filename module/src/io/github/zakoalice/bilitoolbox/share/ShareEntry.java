package io.github.zakoalice.bilitoolbox.share;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.zakoalice.bilitoolbox.Activities;
import io.github.zakoalice.bilitoolbox.Hooks;
import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.Reflect;
import io.github.zakoalice.bilitoolbox.XLog;
import io.github.zakoalice.bilitoolbox.download.VideoDownloader;

/**
 * 往「分享」面板里加一个「下载视频」。
 *
 * <p>哔哩哔哩的分享面板是 Compose / 自绘菜单，硬插 View 是插不进去的。但它的数据是干净的，
 * 而且宿主自己就把「数据 → 菜单项」这步抽成了独立方法，所以我们不碰 UI，只插数据。
 *
 * <p><b>9.17 起的真实面板</b>（{@code com.bilibili.app.comm.supermenu.share.v2}）：
 * <pre>
 *   r.a(Context, ShareChannels, IMenuPanel) : List&lt;IMenuItem&gt;   // 组装整张面板
 *   r.d(ChannelItem) : IMenuItem                                // 单条数据的转换器
 *   r$b.onItemClick(IMenuItem) : boolean                        // 点击分发
 *   ShareChannels { aboveChannels, belowChannels, systemChannels }
 *   ChannelItem   { name, title, picture, shareChannel, ... }
 * </pre>
 * 于是注入只要：hook {@code r.a} 的返回值，把 {@code r.d(自己造的 ChannelItem)} 追加进去。
 * 图标、字号、点击态都由宿主自己画，和原生渠道完全一致。
 *
 * <p><b>9.19 的新架构</b>（{@code kntr.common.share.domain.v1.ChannelList}）是另一套，
 * 只在 {@code supermenu} 那条路走不通时才作为兜底尝试。
 */
public final class ShareEntry {

    /** 9.17+ 的分享面板宿主类。 */
    private static final String PANEL = "com.bilibili.app.comm.supermenu.share.v2.r";
    private static final String PANEL_CLICK = "com.bilibili.app.comm.supermenu.share.v2.r$b";
    private static final String CHANNEL_ITEM =
            "com.bilibili.lib.sharewrapper.online.api.ShareChannels$ChannelItem";

    /**
     * 面板数据的源头。
     *
     * <p>9.17 里能画分享面板的地方不止一个（{@code v2.r}、{@code v2.i}、{@code share.s}…），
     * 但它们**都**是从 {@code ShareChannels#getBelowChannels()} 取列表的。
     * 所以注入放在这个 getter 上，才不会被「换了个构建器」搞死。
     */
    private static final String SHARE_CHANNELS =
            "com.bilibili.lib.sharewrapper.online.api.ShareChannels";

    /** 渠道 → 图标资源 id 的查表器。 */
    private static final String ICON_HELPER =
            "com.bilibili.app.comm.supermenu.share.ShareChannelHelper";

    /** 9.19 的新架构（兜底）。 */
    private static final String CHANNEL_LIST = "kntr.common.share.domain.v1.ChannelList";
    private static final String ITEM = "kntr.common.share.domain.v1.ChannelList$Item";
    private static final String CUSTOM = "kntr.common.share.domain.v1.ShareTarget$Custom";
    private static final String BRIDGE = "kntr.common.share.common.ui.util.ShareViewModelBridgeKt";
    private static final String CLICK_EVENT = "kntr.common.share.domain.b$a";

    /**
     * 塞进 ChannelItem.name 的标记，也是「这是我们那一项」的依据之一。
     *
     * <p>注意 shareChannel 不能用它 —— 那一位决定图标，得填宿主认识的 {@link #ICON_CHANNEL}。
     */
    public static final String MARK = "bilitoolbox_download";
    public static final String TITLE = "下载视频";

    /**
     * 9.17 的图标来源。
     *
     * <p>宿主用 {@code ShareChannelHelper.getDefaultIconRes(shareChannel)} 查表决定图标，
     * 表里没有的值会退回一个通用分享图标。而 {@code SYS_DOWNLOAD} 在表里，拿到的是下载箭头
     * —— 语义正好对上，比自己塞一张网络图更好看，也不依赖外部资源。
     */
    private static final String ICON_CHANNEL = "SYS_DOWNLOAD";

    /** 9.19 路径用图标 URL。 */
    private static final String ICON =
            "https://i0.hdslb.com/bfs/static/jinkela/long/images/favicon.ico";

    /** 9.17 路径的 ChannelItem 缓存。 */
    private static volatile Object cachedChannelItem;
    /** 9.19 路径的 ChannelList$Item 缓存（两者类型不同，不能共用一个字段）。 */
    private static volatile Object cachedLegacyItem;
    private static volatile Method cachedConvert;
    private static volatile boolean injectedOnce;
    private static volatile boolean getterReadLogged;
    private static volatile boolean panelHookLogged;
    /** 宿主里 {@code SYS_DOWNLOAD} 对应的图标资源 id，安装时先问一次。 */
    private static volatile int downloadIconRes;
    private static volatile long lastFireAt;
    private static volatile ClassLoader hostLoader;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 等分享面板退场的时间（毫秒）：面板可能是独立页面，动画没走完就弹框会挂错页面。 */
    private static final long PANEL_CLOSE_DELAY = 260L;

    /** 我们插进去的菜单项引用，点击时用来认领。 */
    private static final List<Object> ours = new ArrayList<>();

    private ShareEntry() {
    }

    public static void install(ClassLoader cl) {
        hostLoader = cl;
        // 9.17 起视频分享面板是 Kotlin + Compose 重写的 kntr.common.share，
        // 跟 supermenu 那套没有调用关系（SuperMenu.addMenus 全 APK 零调用点）。
        // 所以先走新框架，挂上了就不用再挂老的一堆。
        if (KntrShareEntry.install(cl)) {
            return;
        }
        XLog.w("分享入口：新面板挂不上，回退 supermenu / 9.19 老路径");
        SuperMenuEntry.install(cl);
        boolean ok = installSupermenu(cl);
        if (!ok) {
            XLog.w("supermenu 面板没挂上，改用 9.19 的 ChannelList 兜底");
            installLegacy(cl);
        }
        // 主要注入点：不管面板是哪个构建器画的，都得从这个 getter 取列表
        installChannelGetter(cl);
        installIconHelper(cl);
        hookPanelClick(cl);
        hookClickEventLegacy(cl);
    }

    // ========================================================== 通用注入点（主）

    /**
     * 往 {@code getBelowChannels()} 的返回值里补一项。
     *
     * <p>为什么不 hook 面板构建器：9.17 里能画分享面板的构建器有好几个
     * （{@code v2.r} 带 loading、{@code v2.i} 用现成数据、{@code share.s} 走文本分享），
     * 各自的方法名签名都不一样，而且随时可能被服务端开关切走。
     * 但它们的共同点很硬：**都从这个 getter 拿「第二排图标」的列表**。
     * 拦在这里，谁来画都能拿到我们那一项。
     */
    private static void installChannelGetter(ClassLoader cl) {
        Class<?> channels = Reflect.findClass(SHARE_CHANNELS, cl);
        if (channels == null) {
            XLog.w("分享入口：找不到 " + SHARE_CHANNELS);
            return;
        }
        Method getBelow = null;
        try {
            getBelow = channels.getDeclaredMethod("getBelowChannels");
        } catch (Throwable ignored) {
            // 下面统一报错
        }
        if (getBelow == null) {
            XLog.w("分享入口：ShareChannels 没有 getBelowChannels");
            return;
        }
        try {
            XposedBridge.hookMethod(getBelow, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object patched = decorateChannels(param.getResult());
                        if (patched != null) {
                            param.setResult(patched);
                        }
                    } catch (Throwable t) {
                        XLog.w("分享列表增强失败：" + t);
                    }
                }
            });
            XLog.i("分享列表已挂钩 ShareChannels#getBelowChannels");
        } catch (Throwable t) {
            XLog.e("hook getBelowChannels 失败", t);
        }
    }

    /** 返回 null 表示「不动原值」。 */
    @SuppressWarnings("unchecked")
    private static Object decorateChannels(Object value) {
        if (!Prefs.shareEntryEnabled()) {
            return null;
        }
        if (!(value instanceof List)) {
            return null;
        }
        // 这个 getter 不只给面板用：序列化请求体时也会走一遍。
        // 那里塞进去的项会被发到服务端，属于「不该动的地方」，直接放过。
        if (callerIsSerializer()) {
            return null;
        }
        List<Object> list = (List<Object>) value;
        if (!getterReadLogged) {
            getterReadLogged = true;
            // 记下是谁在要这份列表 —— 以后换版本，看这行就知道面板换到哪去了
            XLog.i("分享列表被读取（" + list.size() + " 项），调用方 " + firstHostFrame());
        }
        if (channelsContainOurs(list)) {
            return null;
        }
        Object item = channelItem();
        if (item == null) {
            return null;
        }
        List<Object> copy = new ArrayList<>(list.size() + 1);
        copy.addAll(list);
        copy.add(item);
        if (!injectedOnce) {
            injectedOnce = true;
            XLog.i("分享渠道已注入：" + TITLE);
        }
        return copy;
    }

    /** 列表中是否已经有我们那一项（按 name / shareChannel 认，标记是自己造的不会撞）。 */
    private static boolean channelsContainOurs(List<Object> list) {
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            Object name = Reflect.call(item, "getName");
            Object way = Reflect.call(item, "getShareChannel");
            if (MARK.equals(way) || MARK.equals(name) || TITLE.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 第一个属于宿主的栈帧，用来定位「谁在读分享列表」。 */
    private static String firstHostFrame() {
        String frame = hostFrame();
        return frame == null ? "?" : frame;
    }

    private static String hostFrame() {
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            String cn = e.getClassName();
            if (cn.startsWith("com.bilibili.") || cn.startsWith("tv.danmaku.")) {
                return cn + "#" + e.getMethodName();
            }
        }
        return null;
    }

    /** 调用方是 JSON 序列化器？ */
    private static boolean callerIsSerializer() {
        String frame = hostFrame();
        if (frame == null) {
            return false;
        }
        return frame.contains("JsonDescriptor")
                || frame.contains("TypeAdapter")
                || frame.contains("gson");
    }

    /**
     * 让 {@link #MARK} 也能拿到下载图标。
     *
     * <p>用 {@code SYS_DOWNLOAD} 当 shareChannel 能白嫖宿主图标，但那个渠道宿主是真会去处理的
     * —— 万一我们没拦住点击，就会触发它自己的下载逻辑。所以渠道填自己的标记，
     * 图标则在这个查表函数里替换成 {@code SYS_DOWNLOAD} 的资源 id。
     */
    private static void installIconHelper(ClassLoader cl) {
        Class<?> helper = Reflect.findClass(ICON_HELPER, cl);
        if (helper == null) {
            XLog.w("分享入口：找不到 " + ICON_HELPER + "（图标会退成通用分享图标）");
            return;
        }
        try {
            Method get = helper.getDeclaredMethod("getDefaultIconRes", String.class);
            get.setAccessible(true);
            Object res = get.invoke(null, ICON_CHANNEL);
            if (res instanceof Integer) {
                downloadIconRes = (Integer) res;
            }
            XposedBridge.hookMethod(get, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || param.args.length != 1) {
                        return;
                    }
                    if (MARK.equals(param.args[0]) && downloadIconRes != 0) {
                        param.setResult(downloadIconRes);
                    }
                }
            });
            XLog.i("分享图标已挂钩，下载图标 res=0x" + Integer.toHexString(downloadIconRes));
        } catch (Throwable t) {
            XLog.w("hook 分享图标失败：" + t);
        }
    }

    // ================================================================ 9.17 主路径

    private static boolean installSupermenu(ClassLoader cl) {
        Class<?> panel = Reflect.findClass(PANEL, cl);
        if (panel == null) {
            XLog.w("分享入口：找不到 " + PANEL);
            return false;
        }
        Method build = findBuildMethod(panel);
        if (build == null) {
            XLog.w("分享入口：在 " + PANEL + " 里没找到「(Context, ShareChannels, IMenuPanel) -> List」");
            return false;
        }
        cachedConvert = findConvertMethod(panel);
        if (cachedConvert == null) {
            XLog.w("分享入口：没找到 ChannelItem 转换方法，放弃");
            return false;
        }
        try {
            XposedBridge.hookMethod(build, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        injectInto(param.thisObject, param.getResult());
                    } catch (Throwable t) {
                        XLog.w("分享渠道注入失败：" + t);
                    }
                }
            });
            XLog.i("分享面板已挂钩 " + build.getDeclaringClass().getSimpleName()
                    + "#" + build.getName());
        } catch (Throwable t) {
            XLog.e("hook 分享面板失败", t);
            return false;
        }
        return true;
    }

    /** 按签名找：非静态、返回 List、3 个参数且第 1 个是 Context、第 2 个是 ShareChannels。 */
    private static Method findBuildMethod(Class<?> panel) {
        for (Method m : panel.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            if (!List.class.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 3) {
                continue;
            }
            if (!"android.content.Context".equals(ps[0].getName())) {
                continue;
            }
            if (!ps[1].getName().endsWith("ShareChannels")) {
                continue;
            }
            return m;
        }
        return null;
    }

    /** 按签名找：单参数、参数是 ChannelItem、返回菜单项接口。 */
    private static Method findConvertMethod(Class<?> panel) {
        for (Method m : panel.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1) {
                continue;
            }
            if (!CHANNEL_ITEM.equals(ps[0].getName())) {
                continue;
            }
            m.setAccessible(true);
            return m;
        }
        return null;
    }

    /** 把我们的项追加到面板列表末尾。列表是宿主 new 的 ArrayList，直接 add 即可。 */
    @SuppressWarnings("unchecked")
    private static void injectInto(Object panel, Object result) {
        if (!panelHookLogged) {
            panelHookLogged = true;
            // 这一行只为排查：证明 v2.r 这条路径到底跑没跑
            XLog.i("v2.r#a 已执行，返回值 " + (result == null ? "null" : result.getClass().getName()));
        }
        if (!Prefs.shareEntryEnabled()) {
            return;
        }
        if (!(result instanceof List)) {
            return;
        }
        List<Object> list = (List<Object>) result;
        if (containsOursByTitle(list)) {
            return;
        }
        Object channelItem = channelItem();
        if (channelItem == null) {
            return;
        }
        Object menuItem;
        try {
            menuItem = cachedConvert.invoke(panel, channelItem);
        } catch (Throwable t) {
            XLog.w("转换菜单项失败：" + t);
            return;
        }
        if (menuItem == null) {
            XLog.w("转换菜单项返回 null");
            return;
        }
        list.add(menuItem);
        synchronized (ours) {
            // 每次开面板宿主都会重建一批菜单项，这里跟着换新，别让旧实例一直堆着。
            // 万一清早了（比如复用旧面板），isOurs() 里还有标题兜底。
            ours.clear();
            ours.add(menuItem);
        }
        if (!injectedOnce) {
            injectedOnce = true;
            XLog.i("分享渠道已注入：" + TITLE);
        }
    }

    /** 造一个宿主格式的 ChannelItem（照着宿主自己解析服务端 JSON 的写法抄的）。 */
    private static Object channelItem() {
        Object cached = cachedChannelItem;
        if (cached != null) {
            return cached;
        }
        Class<?> type = Reflect.findClass(CHANNEL_ITEM, hostLoader);
        if (type == null) {
            XLog.w("分享入口：找不到 " + CHANNEL_ITEM);
            return null;
        }
        Object item = Reflect.newInstance(type);
        if (item == null) {
            XLog.w("分享入口：ChannelItem 无参构造失败");
            return null;
        }
        // 注意字段语义（照 v2/i#d 的构造参数顺序反推的）：
        //   name        → 菜单项显示的文字（也就是 getTitle()）
        //   shareChannel→ 分发标识，宿主按它决定点击后干什么、以及查图标
        // 所以 name 必须是给人看的中文，shareChannel 才是我们的标记。
        Reflect.call(item, "setName", TITLE);
        Reflect.call(item, "setTitle", TITLE);
        Reflect.call(item, "setShareChannel", MARK);
        // picture 故意留空：填了会被 setIcon(URL) 覆盖，反而丢掉宿主的下载图标
        cachedChannelItem = item;
        XLog.i("分享渠道已构造：" + TITLE);
        return item;
    }

    private static void hookPanelClick(ClassLoader cl) {
        // 面板的点击监听器都是「面板类的内部类」，而且名字被 R8 混淆成单字母。
        // 不去猜名字：把几个已知面板类的内部类全过一遍，认签名 onItemClick(IMenuItem)。
        String[] hosts = {
                PANEL,
                "com.bilibili.app.comm.supermenu.share.v2.i",
                "com.bilibili.app.comm.supermenu.share.s",
                "com.bilibili.app.comm.supermenu.share.v2.n",
        };
        int hooked = 0;
        for (String host : hosts) {
            Class<?> c = Reflect.findClass(host, cl);
            if (c == null) {
                continue;
            }
            hooked += hookListeners(c);
            Class<?>[] inners;
            try {
                inners = c.getDeclaredClasses();
            } catch (Throwable t) {
                // 内部类里只要有一个依赖缺失，这里就会抛 NoClassDefFoundError，
                // 导致一个都挂不上 —— 所以下面还有一份写死名字的清单兜着。
                inners = new Class<?>[0];
            }
            for (Class<?> inner : inners) {
                hooked += hookListeners(inner);
            }
        }
        // 写死几个已知的内部类名（不依赖 getDeclaredClasses）
        String[] known = {
                PANEL + "$b",
                PANEL + "$a",
                "com.bilibili.app.comm.supermenu.share.r$d",
                "com.bilibili.app.comm.supermenu.share.r$a",
        };
        for (String name : known) {
            Class<?> c = Reflect.findClass(name, cl);
            if (c != null) {
                hooked += hookListeners(c);
            }
        }
        if (hooked == 0) {
            XLog.w("分享入口：没找到任何 onItemClick(IMenuItem)，点击可能无响应");
        } else {
            XLog.i("分享点击已挂 " + hooked + " 个 onItemClick");
        }
    }

    private static int hookListeners(Class<?> type) {
        int hooked = 0;
        Method[] declared;
        try {
            declared = Reflect.methods(type).toArray(new Method[0]);
        } catch (Throwable t) {
            return 0;
        }
        for (Method m : declared) {
            if (Modifier.isStatic(m.getModifiers()) || !"onItemClick".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 1 || !ps[0].getName().endsWith("IMenuItem")) {
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
                            if (!isOurs(param.args[0])) {
                                return;
                            }
                            // 吃掉这次点击，别让宿主按我们的标记去分发
                            param.setResult(Boolean.TRUE);
                            fire();
                        } catch (Throwable t) {
                            XLog.w("分享项点击判定失败：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 同一个方法可能被多个入口重复 hook，跳过
            }
        }
        return hooked;
    }

    /** 引用相同，或者标题就是我们的（across 重组重建实例的情况）。 */
    private static boolean isOurs(Object menuItem) {
        if (menuItem == null) {
            return false;
        }
        synchronized (ours) {
            for (Object o : ours) {
                if (o == menuItem) {
                    return true;
                }
            }
        }
        Object way = Reflect.call(menuItem, "getShareWay");
        if (MARK.equals(way)) {
            return true;
        }
        Object title = Reflect.call(menuItem, "getTitle");
        return title != null && TITLE.contentEquals(title.toString());
    }

    private static boolean containsOursByTitle(List<Object> list) {
        for (Object item : list) {
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

    /** 点击命中之后：找到 Activity 并弹下载面板。两道保险可能同时命中，这里按时间节流。 */
    static synchronized void fire() {
        fire(0L, null);
    }

    /**
     * @param aid  分享参数里带的视频 id（没有就传 0，下载器会退回读 Intent）
     * @param bvid 同上
     *
     * <p>不当场弹：分享面板可能是独立页面，点完它还在退场，这会儿建 Dialog 很容易挂到
     * 一个正在 finish 的页面上 —— 结果就是「点了没反应」，等回到首页才冒出来。
     * 所以先让面板收摊，再取当前页面。
     */
    static synchronized void fire(final long aid, final String bvid) {
        long now = System.currentTimeMillis();
        if (now - lastFireAt < 1000L) {
            return;
        }
        lastFireAt = now;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                show(aid, bvid, 0);
            }
        }, PANEL_CLOSE_DELAY);
    }

    /** 面板收摊后再确认一次页面：拿不到 / 已经在 finish 就再来一轮，最多 3 次。 */
    private static void show(final long aid, final String bvid, final int attempt) {
        Activity activity = Activities.top();
        if (activity == null || activity.isFinishing()) {
            if (attempt < 3) {
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        show(aid, bvid, attempt + 1);
                    }
                }, 200L);
                return;
            }
            XLog.w("分享项被点了，但找不到可用的 Activity（页面栈跟踪"
                    + (Activities.tracking() ? "已启用" : "不可用") + "）");
            return;
        }
        XLog.i("分享面板里点了「" + TITLE + "」，挂在 " + activity.getClass().getSimpleName());
        VideoDownloader.openPicker(activity, aid, bvid);
    }

    // ================================================================ 9.19 兜底路径

    private static void installLegacy(ClassLoader cl) {
        Class<?> channelList = Reflect.findClass(CHANNEL_LIST, cl);
        if (channelList == null) {
            XLog.w("分享入口：9.19 的 " + CHANNEL_LIST + " 也没有，这个版本暂不支持");
            return;
        }
        hookLegacyConstructors(channelList);
        hookLegacyList(channelList, "getBelow");
        hookLegacyList(channelList, "getAbove");
        hookLegacyClick(cl);
    }

    /**
     * ChannelList 是 data class 且字段 public，实测 Compose 直接读字段、不调 getAbove/getBelow，
     * 所以只能在对象造出来的时候改字段。
     */
    private static void hookLegacyConstructors(Class<?> channelList) {
        int hooked = 0;
        for (Constructor<?> ctor : channelList.getDeclaredConstructors()) {
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 2) {
                continue;
            }
            if (!List.class.isAssignableFrom(params[0]) || !List.class.isAssignableFrom(params[1])) {
                continue;
            }
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            patchLegacyChannels(param.thisObject);
                        } catch (Throwable t) {
                            XLog.w("分享渠道注入失败：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XLog.w("hook ChannelList 构造失败：" + t);
            }
        }
        XLog.i("分享渠道 hook 构造函数 " + hooked + " 个");
    }

    @SuppressWarnings("unchecked")
    private static void patchLegacyChannels(Object channelList) {
        if (!Prefs.shareEntryEnabled()) {
            return;
        }
        List<List<Object>> lists = legacyListFields(channelList);
        if (lists.isEmpty()) {
            return;
        }
        // a=above, b=below；只往 below 加，避免出现两项。
        // 这里必须用下标而不是 lists.indexOf(target)：两个列表都为空时 equals 成立，
        // indexOf 会返回 0，把项写到 above 上去。
        int targetIndex = lists.size() > 1 ? 1 : 0;
        List<Object> target = lists.get(targetIndex);
        if (legacyContainsOurs(target)) {
            return;
        }
        Object item = legacyItem();
        if (item == null) {
            return;
        }
        List<Object> copy = new ArrayList<>(target.size() + 1);
        copy.addAll(target);
        copy.add(item);
        if (!writeLegacyListField(channelList, targetIndex, copy)) {
            XLog.w("写不回 below 字段，分享项可能不显示");
            return;
        }
        if (!injectedOnce) {
            injectedOnce = true;
            XLog.i("分享渠道已注入（legacy）：" + TITLE);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> legacyListFields(Object target) {
        List<List<Object>> out = new ArrayList<>();
        for (Field f : Reflect.fields(target.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(target);
                if (value instanceof List) {
                    out.add((List<Object>) value);
                }
            } catch (Throwable ignored) {
                // 跳过
            }
        }
        return out;
    }

    private static boolean writeLegacyListField(Object target, int index, List<Object> value) {
        int seen = -1;
        for (Field f : Reflect.fields(target.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            if (++seen != index) {
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(target, value);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static void hookLegacyList(Class<?> channelList, String getter) {
        if (Hooks.hookDeclared(channelList, getter, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    param.setResult(decorateLegacy(param.getResult()));
                } catch (Throwable t) {
                    XLog.w("分享列表增强失败（" + getter + "）：" + t);
                }
            }
        }) == 0) {
            XLog.w("分享入口：没有 " + getter + " 可挂");
        }
    }

    /**
     * 给列表补上我们那一项。
     *
     * <p>不原地修改：Kotlin 那边给的可能是不可变 List，add 会抛 UnsupportedOperationException。
     */
    @SuppressWarnings("unchecked")
    private static Object decorateLegacy(Object value) {
        if (!Prefs.shareEntryEnabled()) {
            return value;
        }
        if (!(value instanceof List)) {
            return value;
        }
        List<Object> list = (List<Object>) value;
        if (legacyContainsOurs(list)) {
            return value;
        }
        Object item = legacyItem();
        if (item == null) {
            return value;
        }
        List<Object> copy = new ArrayList<>(list.size() + 1);
        copy.addAll(list);
        copy.add(item);
        return copy;
    }

    private static boolean legacyContainsOurs(List<Object> list) {
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            Object target = Reflect.call(item, "getTarget");
            if (target == null) {
                target = Reflect.field(item, "a");
            }
            Object name = Reflect.call(target, "getName");
            if (name == null) {
                name = Reflect.field(target, "a");
            }
            if (MARK.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 复用同一个 Item 实例：Compose 每次重组都会读 getBelow()，不必每次 new。 */
    private static Object legacyItem() {
        Object item = cachedLegacyItem;
        if (item != null) {
            return item;
        }
        ClassLoader cl = hostLoader;
        if (cl == null) {
            return null;
        }
        try {
            Class<?> targetClass = Reflect.findClass(CUSTOM, cl);
            Class<?> itemClass = Reflect.findClass(ITEM, cl);
            if (targetClass == null || itemClass == null) {
                XLog.w("找不到分享渠道模型：" + (targetClass == null ? CUSTOM : ITEM));
                return null;
            }
            Constructor<?> targetCtor = targetClass.getDeclaredConstructor(String.class);
            targetCtor.setAccessible(true);
            Object target = targetCtor.newInstance(MARK);

            Constructor<?> itemCtor = itemClass.getDeclaredConstructor(
                    targetClass, String.class, String.class);
            itemCtor.setAccessible(true);
            Object created = itemCtor.newInstance(target, TITLE, ICON);
            cachedLegacyItem = created;
            XLog.i("分享渠道已构造（legacy）：" + TITLE);
            return created;
        } catch (Throwable t) {
            XLog.e("构造分享渠道失败", t);
            return null;
        }
    }

    /**
     * 点击分发点：{@code ShareViewModelBridgeKt} 里那些「第一个参数是 ShareViewModel」的静态方法。
     * 方法名被 R8 压成了单字母，所以按签名认，不按名字。
     */
    private static void hookLegacyClick(ClassLoader cl) {
        Class<?> bridge = Reflect.findClass(BRIDGE, cl);
        if (bridge == null) {
            XLog.w("分享入口：找不到 " + BRIDGE + "，点击可能无响应");
            return;
        }
        int hooks = 0;
        for (Method method : bridge.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            Class<?>[] params = method.getParameterTypes();
            if (params.length < 2 || !params[0].getName().endsWith("ShareViewModel")) {
                continue;
            }
            try {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        handleLegacyClick(param);
                    }
                });
                hooks++;
            } catch (Throwable t) {
                XLog.w("hook 分享点击失败 " + method.getName() + "：" + t);
            }
        }
        XLog.i("分享点击已挂 " + hooks + " 个分发方法");
    }

    private static void handleLegacyClick(XC_MethodHook.MethodHookParam param) {
        if (param.args == null) {
            return;
        }
        for (Object arg : param.args) {
            if (deepHasMark(arg, 4)) {
                try {
                    fire();
                } catch (Throwable t) {
                    XLog.e("分享项点击处理失败", t);
                }
                return;
            }
        }
    }

    /**
     * 第二道保险：点击事件对象在构造时就带着被点的渠道，在这里认领比去猜 bridge 的参数结构更直接。
     */
    private static void hookClickEventLegacy(ClassLoader cl) {
        Class<?> event = Reflect.findClass(CLICK_EVENT, cl);
        if (event == null) {
            return;
        }
        int hooked = 0;
        for (Constructor<?> ctor : event.getDeclaredConstructors()) {
            if (ctor.getParameterTypes().length < 2) {
                continue;
            }
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null) {
                                return;
                            }
                            for (Object arg : param.args) {
                                if (deepHasMark(arg, 4)) {
                                    fire();
                                    return;
                                }
                            }
                        } catch (Throwable t) {
                            XLog.w("分享点击事件判定失败：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                XLog.w("hook 分享点击事件失败：" + t);
            }
        }
        if (hooked > 0) {
            XLog.i("分享点击事件已挂 " + hooked + " 个构造函数");
        }
    }

    /**
     * 在对象图里找标记字符串。
     *
     * <p>点击事件的包装层有两三层（事件 → UI 模型 → 渠道），而每一层的字段名都被混淆了，
     * 与其一层层猜类型，不如直接按「有没有出现过我们那个标记」认 —— 这个标记是我们自己造的，
     * 不会和宿主数据撞车。
     */
    private static boolean deepHasMark(Object value, int depth) {
        if (value == null || depth < 0) {
            return false;
        }
        if (value instanceof String) {
            String s = (String) value;
            return MARK.equals(s) || TITLE.equals(s);
        }
        Class<?> type = value.getClass();
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("android.")
                || name.startsWith("kotlin.") || value instanceof Number
                || value instanceof Boolean) {
            return false;
        }
        for (Field field : Reflect.fields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                continue;
            }
            Object child;
            try {
                field.setAccessible(true);
                child = field.get(value);
            } catch (Throwable t) {
                continue;
            }
            if (deepHasMark(child, depth - 1)) {
                return true;
            }
        }
        return false;
    }
}
