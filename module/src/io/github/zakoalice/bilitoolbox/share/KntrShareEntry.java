package io.github.zakoalice.bilitoolbox.share;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.Reflect;
import io.github.zakoalice.bilitoolbox.XLog;

/**
 * 往 9.17 的「新分享面板」里加一项。
 *
 * <p><b>为什么之前四套 hook 全都没响</b>：哔哩哔哩 9.17 的视频分享面板已经不是
 * {@code com.bilibili.app.comm.supermenu} 那一套了（那套类还在，但 {@code SuperMenu.addMenus}
 * 在整个 APK 里**一个调用点都没有**，是死代码）。真正的面板是 Kotlin + Compose 重写的
 * {@code kntr.common.share}（R8 全局混淆后的包名），入口在
 * {@code com.bilibili.app.gemini.share.q}（播放器 → 分享）。
 *
 * <p><b>数据长这样</b>（{@code domain/service/i} 把服务端下发的渠道 id 查表翻成菜单项）：
 * <pre>
 *   b$a(id, 名字, ImageBitmap, 图标URL, DrawableResource, ...)   // 一条菜单项
 *   e.c(List&lt;List&lt;b$a&gt;&gt;) : List&lt;List&lt;b$a&gt;&gt;                     // 面板渲染前对「行」做过滤/整理
 *   ShareViewModel.c(q, Continuation)                           // 点击事件统一入口，q$a = 点了某一项
 * </pre>
 * 所以注入只要在 {@code e.c} 的返回值里补一条 —— 面板自己会把它画成和微信/QQ 一模一样的按钮；
 * 点击则在 {@code ShareViewModel.c} 上认领：id 是我们的就吞掉，开自己的清晰度选择框。
 *
 * <p>图标不写死：从面板里现有的某一项把 {@code d}（图标 URL）/ {@code e}（本地图标）复制过来，
 * 优先挑「下载/缓存」那一项，挑不到就用最后一项。这样不联网、不依赖资源 id，也不会画成空白。
 */
public final class KntrShareEntry {

    /** 9.17 的菜单项模型（Kotlin data class，字段被压成单字母）。 */
    private static final String ENTRY = "kntr.common.share.domain.model.b$a";
    /** 菜单项提供者：c(List) 在面板渲染前跑一遍。 */
    private static final String PROVIDER = "kntr.common.share.domain.model.e";
    /** 分享 ViewModel：c(q) 是点击/事件的统一入口。 */
    private static final String VIEW_MODEL = "kntr.common.share.domain.ShareViewModel";
    /** 「点了某一项」这个事件。 */
    private static final String CLICK_EVENT = "kntr.common.share.domain.q$a";
    /** 面板状态（里面的 a 字段就是「一行行菜单项」）。 */
    private static final String STATE = "kntr.common.share.domain.model.b";
    /** 面板布局：a(单行横滑) / b(多行) 都是 flatten + chunked + take，最终画的就是这里拿到的列表。 */
    private static final String LAYOUT = "kntr.common.share.common.ui.menu.u";

    /** 我们自己那一项的 id（也是点击时认领的依据）。 */
    static final String ID = "xiaomu_share_download";
    static final String TITLE = "下载视频";

    /**
     * 「分享目标行」的指纹。
     *
     * <p>面板的每一行都是单独一次布局调用（第一行是分享目标，第二行是倍速/镜像这类播放操作），
     * 如果见行就插，上下两行第一位都会出现「下载视频」。所以只认带这些渠道的那一行 ——
     * 微信/朋友圈/QQ/QQ空间/复制链接/更多 是分享目标行固定会有的几个。
     */
    private static final String[] SHARE_ROW_MARKERS = {
            "WEIXIN", "WEIXIN_MONMENT", "QQ", "QZONE", "SINA", "COPY", "GENERIC",
    };

    private static volatile Class<?> entryType;
    private static volatile Class<?> stateType;
    private static volatile Constructor<?> entryCtor;
    private static volatile Method stateCopy;
    private static volatile Object ourEntry;
    private static volatile String lastDataSignature;
    private static volatile String lastRenderSignature;
    private static volatile boolean injectedLogged;
    private static volatile boolean skipLogged;
    private static volatile boolean clickLogged;

    private KntrShareEntry() {
    }

    /** @return true 表示这套框架在，已经接管成功（老的那几套就不用再挂了）。 */
    public static boolean install(ClassLoader cl) {
        Class<?> entry = Reflect.findClass(ENTRY, cl);
        Class<?> provider = Reflect.findClass(PROVIDER, cl);
        if (entry == null || provider == null) {
            return false;
        }
        entryType = entry;
        entryCtor = findCtor(entry);
        if (entryCtor == null) {
            XLog.w("分享面板：b$a 没有 (String,String,String,String,int) 构造，放弃");
            return false;
        }
        if (!hookProvider(provider)) {
            return false;
        }
        Class<?> vm = Reflect.findClass(VIEW_MODEL, cl);
        hookPanelOpen(vm);
        hookClick(vm);
        hookLayout(cl);
        return true;
    }

    /** 面板每开一次就新建一个 ViewModel，这里只留一行日志，用来确认「面板确实走过来了」。 */
    private static void hookPanelOpen(Class<?> viewModel) {
        if (viewModel == null) {
            return;
        }
        for (Constructor<?> ctor : viewModel.getDeclaredConstructors()) {
            try {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        XLog.i("分享面板已打开（kntr.common.share）");
                    }
                });
                return;
            } catch (Throwable ignored) {
                // 换个构造函数再试
            }
        }
    }

    // ------------------------------------------------------------------ 注入

    private static boolean hookProvider(Class<?> provider) {
        Method target = null;
        for (Method m : provider.getDeclaredMethods()) {
            if (!"c".equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 1 && List.class.isAssignableFrom(ps[0])
                    && List.class.isAssignableFrom(m.getReturnType())) {
                target = m;
                break;
            }
        }
        if (target == null) {
            XLog.w("分享面板：model.e 里没找到 c(List)List");
            return false;
        }
        try {
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        patch(param);
                    } catch (Throwable t) {
                        XLog.w("分享面板注入失败：" + t);
                    }
                }
            });
            XLog.i("分享面板已挂钩（新框架 model.e#c）");
            return true;
        } catch (Throwable t) {
            XLog.e("hook 分享面板失败", t);
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static void patch(XC_MethodHook.MethodHookParam param) {
        if (!Prefs.shareEntryEnabled()) {
            return;
        }
        Object result = param.getResult();
        if (!(result instanceof List)) {
            return;
        }
        List<Object> groups = (List<Object>) result;
        logData(groups);
        if (groups.isEmpty() || containsOurs(groups)) {
            return;
        }
        Object entry = buildEntry(groups);
        if (entry == null) {
            return;
        }
        ArrayList<Object> out = new ArrayList<>(groups.size());
        out.addAll(groups);
        int target = pickRow(out);
        ArrayList<Object> row = new ArrayList<>();
        Object head = out.get(target);
        if (head instanceof Collection) {
            row.addAll((Collection<Object>) head);
        }
        // 关键：插到行首。面板每行是横向滑动的，一屏只露 6 个，
        // 之前 add() 加在末尾会跑到屏幕右侧外面，不滑就看不见。
        row.add(0, entry);
        out.set(target, row);
        param.setResult(out);
        ourEntry = entry;
        if (!injectedLogged) {
            injectedLogged = true;
            XLog.i("分享面板已注入：" + TITLE + "（第 " + (target + 1) + " 行第 1 位，该行现有 " + row.size() + " 项）");
        }
    }

    /**
     * 选插到哪一行：优先第一行（最显眼、一定在初始位置），第一行为空就退到第一个非空行。
     */
    private static int pickRow(List<Object> groups) {
        for (int i = 0; i < groups.size(); i++) {
            Object row = groups.get(i);
            if (row instanceof Collection && !((Collection<?>) row).isEmpty()) {
                return i;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------ 渲染层注入

    /**
     * 数据层插进去的项可能被后面的过滤丢掉：实测 9.17 里宿主的 {@code SINA/微博} 明明在数据里、
     * 屏幕上也看不到，说明数据 → 画面之间还有一道白名单。
     *
     * <p>渲染层是最后一站，{@code menu.u.a} / {@code menu.u.b} 拿到 state 后自己做
     * {@code flatten → chunked(每行几格) → take(最多几行)}，画的就是这个结果。
     * 所以在这里把 state 的列表换成「我们的项 + 原有全部」，让它稳稳落在第一行第一格。
     *
     * <p>顺带解决另一个坑：一屏只显示 {@code 每行几格 × 几行} 个（实测横屏 6×2=12 个），
     * 排在后面的项根本不会画出来，插在行尾等于没插。
     */
    private static void hookLayout(ClassLoader cl) {
        Class<?> layout = Reflect.findClass(LAYOUT, cl);
        Class<?> state = Reflect.findClass(STATE, cl);
        if (layout == null || state == null) {
            XLog.w("分享面板：没有渲染层 menu.u / model.b，只靠数据层注入");
            return;
        }
        stateType = state;
        stateCopy = findStateCopy(state);
        int hooked = 0;
        for (Method m : layout.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length < 3 || ps[0] != state) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            patchState(param);
                        } catch (Throwable t) {
                            XLog.w("渲染层注入失败：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 换个重载
            }
        }
        if (hooked > 0) {
            XLog.i("分享面板渲染层已挂钩（menu.u#" + hooked + "）");
        } else {
            XLog.w("分享面板：menu.u 里没有可挂的布局函数");
        }
    }

    @SuppressWarnings("unchecked")
    private static void patchState(XC_MethodHook.MethodHookParam param) {
        if (!Prefs.shareEntryEnabled()) {
            return;
        }
        Object state = param.args[0];
        if (state == null || stateType == null || !stateType.isInstance(state)) {
            return;
        }
        Object raw = read(state, "a");
        if (!(raw instanceof List)) {
            return;
        }
        List<Object> groups = (List<Object>) raw;
        logRender(groups);
        if (!isShareRow(groups)) {
            if (!skipLogged) {
                skipLogged = true;
                XLog.i("分享面板：这一行是播放操作行（倍速/镜像那排），不加下载视频");
            }
            return;
        }
        Object entry = ensureEntry(groups);
        if (entry == null) {
            return;
        }
        ArrayList<Object> flat = new ArrayList<>();
        flat.add(entry);
        for (Object group : groups) {
            if (!(group instanceof Collection)) {
                continue;
            }
            for (Object item : (Collection<Object>) group) {
                if (item == null || ID.equals(read(item, "a"))) {
                    continue;
                }
                flat.add(item);
            }
        }
        ArrayList<Object> rows = new ArrayList<>(1);
        rows.add(flat);
        if (replaceState(param, state, rows) && !injectedLogged) {
            injectedLogged = true;
            XLog.i("分享面板已注入（渲染层）：" + TITLE + " 在第 1 行第 1 位，共 " + flat.size()
                    + " 项（每行几格由宿主自己算）");
        }
    }

    /** 这一行是不是「分享目标行」？见 {@link #SHARE_ROW_MARKERS}。 */
    private static boolean isShareRow(List<Object> groups) {
        for (Object group : groups) {
            if (!(group instanceof Collection)) {
                continue;
            }
            for (Object item : (Collection<?>) group) {
                Object id = read(item, "a");
                if (id == null) {
                    continue;
                }
                String text = id.toString();
                for (String marker : SHARE_ROW_MARKERS) {
                    if (marker.equals(text)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** 我们的菜单项只造一次，之后复用（图标要从面板里现成项抄，所以第一次得等有数据）。 */
    private static Object ensureEntry(List<Object> groups) {
        Object cached = ourEntry;
        if (cached != null) {
            return cached;
        }
        Object entry = buildEntry(groups);
        if (entry != null) {
            ourEntry = entry;
        }
        return entry;
    }

    private static boolean replaceState(XC_MethodHook.MethodHookParam param, Object state, List<Object> rows) {
        Method copy = stateCopy;
        if (copy != null) {
            try {
                Object out = copy.invoke(null, state, rows, null, null, 0x3fe);
                if (out != null) {
                    param.args[0] = out;
                    return true;
                }
            } catch (Throwable ignored) {
                // 退回直接改字段
            }
        }
        try {
            Field f = state.getClass().getDeclaredField("a");
            f.setAccessible(true);
            f.set(state, rows);
            return true;
        } catch (Throwable t) {
            XLog.w("渲染层：改不动面板状态：" + t);
            return false;
        }
    }

    /** state.copy(a = 新列表) 的入口：model.b#a(b, List, String, Object, mask)，mask 0x3fe = 其余字段照抄。 */
    private static Method findStateCopy(Class<?> state) {
        for (Method m : state.getDeclaredMethods()) {
            if (!"a".equals(m.getName()) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length == 5 && ps[0] == state && ps[1] == List.class && ps[2] == String.class
                    && ps[3] == Object.class && ps[4] == int.class) {
                try {
                    m.setAccessible(true);
                    return m;
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    /** 不同视频（竖屏/横屏）面板内容不一样，按内容去重，保证每种都打一条日志。 */
    private static void logData(List<?> groups) {
        String sig = describe(groups);
        if (sig.equals(lastDataSignature)) {
            return;
        }
        lastDataSignature = sig;
        XLog.i("分享面板（数据层）" + groups.size() + " 组：" + sig);
    }

    private static void logRender(List<?> groups) {
        String sig = describe(groups);
        if (sig.equals(lastRenderSignature)) {
            return;
        }
        lastRenderSignature = sig;
        XLog.i("分享面板（渲染层）" + groups.size() + " 组：" + sig);
    }

    private static Object read(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Field f : target.getClass().getDeclaredFields()) {
            if (!f.getName().equals(name)) {
                continue;
            }
            try {
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** ViewModel.a 是 model.l，model.l.a 才是 ShareParams —— 剥一到两层找它。 */
    private static Object shareParams(Object viewModel) {
        Object holder = read(viewModel, "a");
        if (holder == null) {
            return null;
        }
        if (holder.getClass().getName().endsWith("ShareParams")) {
            return holder;
        }
        Object inner = read(holder, "a");
        if (inner != null && inner.getClass().getName().endsWith("ShareParams")) {
            return inner;
        }
        return null;
    }

    private static String text(Object value) {
        return value == null ? "-" : value.toString();
    }

    /** 造一条宿主格式的菜单项：id 用我们的，名字用中文，图标抄面板里现成的。 */
    private static Object buildEntry(List<Object> groups) {
        Object template = pickTemplate(groups);
        if (template == null) {
            XLog.w("分享面板：面板里一条都没有，没法抄图标");
            return null;
        }
        Object iconUrl = Reflect.field(template, "d");
        try {
            Object entry = entryCtor.newInstance(ID, TITLE, iconUrl, null, 0x18);
            // 面板优先用 ImageBitmap / DrawableResource 时也能拿到图标（set 失败不影响 URL 那条）
            copyField(template, entry, "e");
            copyField(template, entry, "c");
            return entry;
        } catch (Throwable t) {
            XLog.w("构造分享菜单项失败：" + t);
            return null;
        }
    }

    /** 优先抄「下载/缓存」那一项的图标，没有再退到最后一项。 */
    private static Object pickTemplate(List<Object> groups) {
        Object fallback = null;
        Object best = null;
        for (Object group : groups) {
            if (!(group instanceof Collection)) {
                continue;
            }
            for (Object entry : (Collection<?>) group) {
                if (entry == null) {
                    continue;
                }
                fallback = entry;
                Object id = Reflect.field(entry, "a");
                String text = id == null ? "" : id.toString().toUpperCase();
                if (text.contains("DOWNLOAD") || text.contains("CACHE") || text.contains("SAVE")) {
                    best = entry;
                }
            }
        }
        return best != null ? best : fallback;
    }

    private static void copyField(Object from, Object to, String name) {
        Object value = null;
        for (Field f : Reflect.fields(from.getClass())) {
            if (!f.getName().equals(name)) {
                continue;
            }
            try {
                f.setAccessible(true);
                value = f.get(from);
            } catch (Throwable ignored) {
                // 读不到就算了
            }
        }
        if (value != null) {
            Reflect.setField(to, name, value);
        }
    }

    private static boolean containsOurs(List<?> groups) {
        for (Object group : groups) {
            if (!(group instanceof Collection)) {
                continue;
            }
            for (Object entry : (Collection<?>) group) {
                if (entry == null) {
                    continue;
                }
                if (ID.equals(Reflect.field(entry, "a"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 把「几行几项、都是些什么」写进日志，换版本时一眼看出面板去哪了。 */
    private static String describe(List<?> groups) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groups.size(); i++) {
            Object group = groups.get(i);
            int size = group instanceof Collection ? ((Collection<?>) group).size() : 0;
            if (i > 0) {
                sb.append(" | ");
            }
            sb.append("第").append(i + 1).append("行 ").append(size).append(" 项[");
            if (group instanceof Collection) {
                boolean first = true;
                for (Object entry : (Collection<?>) group) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    Object id = Reflect.field(entry, "a");
                    Object name = Reflect.field(entry, "b");
                    sb.append(id).append('/').append(name);
                }
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static Constructor<?> findCtor(Class<?> type) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            Class<?>[] ps = ctor.getParameterTypes();
            if (ps.length != 5) {
                continue;
            }
            if (ps[0] != String.class || ps[1] != String.class || ps[2] != String.class
                    || ps[3] != String.class || ps[4] != int.class) {
                continue;
            }
            try {
                ctor.setAccessible(true);
                return ctor;
            } catch (Throwable ignored) {
                // 下一个
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 点击

    /**
     * 面板点任何一项都会走 {@code ShareViewModel.c(q, ...)}，而 {@code q$a} 里就带着被点的那一项。
     * 在这里认领最稳：既不用管面板怎么画的，也不用猜点击是怎么分发到各渠道的。
     */
    private static void hookClick(Class<?> viewModel) {
        if (viewModel == null) {
            XLog.w("分享面板：找不到 ShareViewModel，点击可能没反应");
            return;
        }
        int hooked = 0;
        for (Method m : viewModel.getDeclaredMethods()) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length != 2 || !"kntr.common.share.domain.q".equals(ps[0].getName())) {
                continue;
            }
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (claim(param)) {
                                param.setResult(null);
                            }
                        } catch (Throwable t) {
                            XLog.w("分享面板点击判定失败：" + t);
                        }
                    }
                });
                hooked++;
            } catch (Throwable ignored) {
                // 有别的重载
            }
        }
        if (hooked > 0) {
            XLog.i("分享面板点击已挂钩（ShareViewModel#" + hooked + "）");
        } else {
            XLog.w("分享面板：ShareViewModel 没有 c(domain.q)，点击可能没反应");
        }
    }

    private static boolean claim(XC_MethodHook.MethodHookParam param) {
        if (param.args == null || param.args.length == 0) {
            return false;
        }
        Object event = param.args[0];
        if (event == null || !CLICK_EVENT.equals(event.getClass().getName())) {
            return false;
        }
        Object entry = Reflect.field(event, "a");
        if (entry == null) {
            return false;
        }
        Object id = Reflect.field(entry, "a");
        if (!clickLogged) {
            clickLogged = true;
            // 只为排查：证明点击确实从这里过，以及 id 长什么样
            XLog.i("分享面板点击：" + id + " / " + Reflect.field(entry, "b"));
        }
        if (ID.equals(id) || entry == ourEntry) {
            // 顺手把视频 id 从面板自己的参数里掏出来：有些页面（竖屏/推荐流）Intent 里没有
            // aid/bvid，只靠 Intent 会提示「读不到视频信息」。
            long aid = 0;
            String bvid = null;
            Object params = shareParams(param.thisObject);
            if (params != null) {
                String oid = text(Reflect.call(params, "getOid"));
                if (oid.startsWith("BV")) {
                    bvid = oid;
                } else if (oid.matches("\\d{1,12}")) {
                    aid = Long.parseLong(oid);
                } else if (oid.matches("\\d{13,}")) {
                    // 15~19 位那种是动态 id / 会话 id，不是 aid；当成 aid 会去下别的视频
                    XLog.w("分享参数里的 oid 不像视频 id（" + oid + "），忽略，改用页面里的");
                }
                XLog.i("分享参数：scene=" + text(Reflect.call(params, "getScene"))
                        + " oid=" + oid + " sid=" + text(Reflect.call(params, "getSid")));
            }
            ShareEntry.fire(aid, bvid);
            return true;
        }
        return false;
    }
}
