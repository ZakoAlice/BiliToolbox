package io.github.zakoalice.bilitoolbox.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import io.github.zakoalice.bilitoolbox.Hooks;
import io.github.zakoalice.bilitoolbox.Prefs;
import io.github.zakoalice.bilitoolbox.Reflect;
import io.github.zakoalice.bilitoolbox.XLog;

/**
 * 往「我的 → 更多服务」里加一个「小睦工具箱」入口。
 *
 * <p>这条列表是服务端下发的：一个 {@code MenuGroup} 卡片装着一串 {@code MenuGroup.Item}
 * （标题 + 图标 + uri）。所以不需要自己造 View，只要往正确的 group 的 itemList 里塞一个
 * Item，宿主就会用自己那套网格单元把它画出来 —— 颜色、圆角、点击涟漪全都自动一致。
 *
 * <p>难点有三个，代码里的对策也对应三条：
 * <ul>
 *   <li>数据在 {@code MenuGroup} 里，但它藏在 adapter 的某个 List 字段里，字段名会被 R8 改
 *       —— 所以按「列表首元素的类名」认，不认字段名。</li>
 *   <li>列表是异步下发的，onViewCreated 那一刻还没到 —— 所以注入要重试。</li>
 *   <li>Item 的 uri 走宿主路由，我们的设置界面不是 Activity，跳不过去 —— 所以 hook 网格
 *       holder 的 onClick(View)，只在点到自己那一格时接管。</li>
 * </ul>
 */
public final class MineEntry {

    /** 手机版「我的」页的 Fragment。HD 版类名不同，下面会按包名拼。 */
    private static final String FRAGMENT_PHONE = "tv.danmaku.bili.ui.main2.mine.HomeUserCenterFragment";
    private static final String FRAGMENT_HD = "tv.danmaku.bilibilihd.ui.main2.mine.HomeUserCenterFragment";

    private static final String GROUP = "com.bilibili.lib.homepage.mine.MenuGroup";
    private static final String ITEM = "com.bilibili.lib.homepage.mine.MenuGroup$Item";

    private static final String URI = "bilitoolbox://settings";
    private static final String TITLE = "小睦工具箱";

    /** 'TOOL' —— 随便取，只要和宿主下发的 id 不撞。 */
    private static final long ITEM_ID = 0x544F4F4CL;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Set<Class<?>> BIND_HOOKED =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());
    private static final Set<Class<?>> CLICK_HOOKED =
            Collections.newSetFromMap(new WeakHashMap<Class<?>, Boolean>());

    private static volatile boolean announced;

    private MineEntry() {
    }

    public static void install(ClassLoader cl, String packageName) {
        String fragment = FRAGMENT_PHONE;
        if (packageName != null && packageName.contains("hd")) {
            fragment = FRAGMENT_HD;
        }
        Class<?> mine = Reflect.findClass(fragment, cl);
        if (mine == null) {
            XLog.w("我的页入口跳过：找不到 " + fragment);
            return;
        }
        XC_MethodHook onShow = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // 兜底挂到父类时，别的 Fragment 也会进来，这里筛掉
                if (mine.isInstance(param.thisObject) && Prefs.entryEnabled()) {
                    schedule(param.thisObject, mine);
                }
            }
        };
        Hooks.hookWithParents(mine, cl, "onResume", Hooks.FRAGMENT_PARENTS, onShow);
        Hooks.hookWithParents(mine, cl, "onViewCreated", Hooks.FRAGMENT_PARENTS, onShow);
    }

    /**
     * 列表是服务端下发的，注入要等它到。这里每 500ms 试一次、最多 10 秒，
     * 一旦塞进去了就立刻停下 —— 免得用户每次回到「我的」页都白跑 21 轮。
     */
    private static void schedule(final Object fragment, final Class<?> mineClass) {
        if (fragment == null || !mineClass.isInstance(fragment)) {
            return;
        }
        retry(fragment, 0);
    }

    private static void retry(final Object fragment, final int attempt) {
        if (attempt > 20) {
            return;
        }
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean done = false;
                try {
                    done = inject(fragment);
                } catch (Throwable t) {
                    XLog.e("我的页注入异常", t);
                }
                if (!done) {
                    retry(fragment, attempt + 1);
                }
            }
        }, attempt == 0 ? 0L : 500L);
    }

    /** @return 是否已经成功把入口塞进列表 */
    private static boolean inject(Object fragment) {
        Context context = contextOf(fragment);
        if (context == null) {
            return false;
        }
        ContextHolder.context = context;
        Object view = Reflect.call(fragment, "getView");
        if (!(view instanceof View)) {
            return false;
        }
        Object adapter = findAdapter((View) view);
        if (adapter == null) {
            return false;
        }
        hookBind(adapter);
        if (ensure(adapter, context, true)) {
            if (!announced) {
                announced = true;
                XLog.i("「更多服务」已插入小睦工具箱入口");
            }
            return true;
        }
        return false;
    }

    private static Context contextOf(Object fragment) {
        Object ctx = Reflect.call(fragment, "getContext");
        if (ctx instanceof Context) {
            return (Context) ctx;
        }
        ctx = Reflect.call(fragment, "getActivity");
        return ctx instanceof Context ? (Context) ctx : null;
    }

    // --------------------------------------------------------------- 找 adapter

    /**
     * 深度优先找第一个「adapter 里装着 MenuGroup 列表」的 RecyclerView。
     *
     * <p>不能用 {@code instanceof RecyclerView}：宿主有自己的 androidx，模块编译时引用的是
     * SDK 里的一份，两个类对象不相等。只能按类名一路往上比。
     */
    private static Object findAdapter(View root) {
        ArrayList<View> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            View view = stack.remove(stack.size() - 1);
            if (isRecycler(view)) {
                Object adapter = Reflect.call(view, "getAdapter");
                if (adapter != null && menuGroups(adapter) != null) {
                    return adapter;
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    stack.add(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    static boolean isRecycler(View view) {
        for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
            if ("androidx.recyclerview.widget.RecyclerView".equals(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** adapter 里那个元素类型是 MenuGroup 的 List。 */
    @SuppressWarnings("unchecked")
    static List<Object> menuGroups(Object adapter) {
        for (Field f : Reflect.fields(adapter.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(adapter);
                if (!(value instanceof List)) {
                    continue;
                }
                List<Object> list = (List<Object>) value;
                if (list.isEmpty()) {
                    continue;
                }
                Object first = list.get(0);
                if (first != null && GROUP.equals(first.getClass().getName())) {
                    return list;
                }
            } catch (Throwable ignored) {
                // 换下一个字段
            }
        }
        return null;
    }

    /** 某个 group 卡片里的 item 列表（字段名会被混淆，按元素类型找）。 */
    @SuppressWarnings("unchecked")
    static List<Object> itemsOf(Object group) {
        if (group == null) {
            return Collections.emptyList();
        }
        for (Field f : Reflect.fields(group.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(group);
                if (!(value instanceof List)) {
                    continue;
                }
                List<Object> list = (List<Object>) value;
                if (!list.isEmpty() && ITEM.equals(list.get(0).getClass().getName())) {
                    return list;
                }
            } catch (Throwable ignored) {
                // 换下一个
            }
        }
        return Collections.emptyList();
    }

    // ---------------------------------------------------------------- 注入主体

    private static boolean ensure(Object adapter, Context context, boolean notify) {
        List<Object> groups = menuGroups(adapter);
        if (groups == null || groups.isEmpty()) {
            return false;
        }
        if (containsOurItem(groups)) {
            return true;
        }
        int index = pickGroup(groups);
        if (index < 0) {
            return false;
        }
        Object item = buildItem(context);
        if (item == null) {
            return false;
        }
        if (!appendItem(groups.get(index), item)) {
            return false;
        }
        if (notify) {
            try {
                Reflect.call(adapter, "notifyItemChanged", index);
            } catch (Throwable t) {
                XLog.w("notifyItemChanged 失败：" + t);
            }
        }
        return true;
    }

    private static boolean containsOurItem(List<Object> groups) {
        for (Object group : groups) {
            for (Object item : itemsOf(group)) {
                if (URI.equals(Reflect.field(item, "uri"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 优先塞进标题/内容带「更多服务」的那张卡片，找不到就退到最后一个非空卡片。 */
    private static int pickGroup(List<Object> groups) {
        int fallback = -1;
        for (int i = 0; i < groups.size(); i++) {
            Object group = groups.get(i);
            String title = String.valueOf(Reflect.field(group, "title"));
            for (Object item : itemsOf(group)) {
                if (String.valueOf(Reflect.field(item, "title")).contains("更多服务")) {
                    return i;
                }
            }
            if (title.contains("更多服务")) {
                return i;
            }
            if (i > 0 && title.contains("服务") && !itemsOf(group).isEmpty()) {
                fallback = i;
            }
        }
        if (fallback >= 0) {
            return fallback;
        }
        for (int i = groups.size() - 1; i >= 0; i--) {
            if (!itemsOf(groups.get(i)).isEmpty()) {
                return i;
            }
        }
        return -1;
    }

    @SuppressWarnings("unchecked")
    private static boolean appendItem(Object group, Object item) {
        for (Field f : Reflect.fields(group.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object value = f.get(group);
                if (value instanceof List) {
                    List<Object> list = (List<Object>) value;
                    if (list.isEmpty() || ITEM.equals(list.get(0).getClass().getName())) {
                        list.add(item);
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 继续
            }
        }
        // 一个 item 列表都没有的卡片：造一个塞回去
        for (Field f : Reflect.fields(group.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !List.class.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                List<Object> created = new ArrayList<>();
                created.add(item);
                f.setAccessible(true);
                f.set(group, created);
                return true;
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return false;
    }

    /** 造一个 MenuGroup.Item。图标直接借宿主的 ic_mine_setting，不用自带资源。 */
    private static Object buildItem(Context context) {
        Class<?> itemClass = Reflect.findClass(ITEM, context.getClassLoader());
        if (itemClass == null) {
            XLog.w("找不到 " + ITEM);
            return null;
        }
        Object item = Reflect.newInstance(itemClass);
        if (item == null) {
            XLog.w("MenuGroup$Item 无参构造失败");
            return null;
        }
        // id 在不同版本被 R8 改过名，两个都试
        if (!Reflect.setField(item, new String[]{"id", "f267193id"}, ITEM_ID)) {
            XLog.w("写 item.id 失败（不影响显示）");
        }
        Reflect.setField(item, new String[]{"title"}, TITLE);
        Reflect.setField(item, new String[]{"uri"}, URI);
        Reflect.setField(item, new String[]{"needLogin"}, 0);
        Reflect.setField(item, new String[]{"visible"}, 0);
        Reflect.setField(item, new String[]{"localRedDot"}, 0);

        int icon = 0;
        for (String name : new String[]{"ic_mine_setting", "ic_mine_feedback", "ic_mine_help"}) {
            icon = context.getResources().getIdentifier(name, "drawable", context.getPackageName());
            if (icon != 0) {
                break;
            }
        }
        if (icon != 0) {
            Reflect.setField(item, new String[]{"iconResId"}, icon);
        } else {
            XLog.w("没找到宿主图标，入口可能只显示文字");
        }
        return item;
    }

    // ---------------------------------------------------------------- 点击接管

    /**
     * 每次 bind 都重新确保一遍（服务端刷新会把我们塞的 item 冲掉），
     * 同时给「更多服务」那张卡片的内层网格挂点击。
     */
    private static void hookBind(final Object adapter) {
        final Class<?> type = adapter.getClass();
        if (!BIND_HOOKED.add(type)) {
            return;
        }
        try {
            XposedBridge.hookAllMethods(type, "onBindViewHolder", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        handleBind(adapter, param.args);
                    } catch (Throwable t) {
                        XLog.w("bind 处理异常：" + t);
                    }
                }
            });
            XLog.i("菜单 adapter 已挂钩 " + type.getName());
        } catch (Throwable t) {
            XLog.e("hook onBindViewHolder 失败", t);
        }
    }

    private static void handleBind(Object adapter, Object[] args) {
        if (args == null || args.length < 2) {
            return;
        }
        Object holder = args[0];
        Object position = args[1];
        if (!(position instanceof Integer)) {
            return;
        }
        List<Object> groups = menuGroups(adapter);
        if (groups == null) {
            return;
        }
        int pos = (Integer) position;
        // bind 里不能 notify，会 ConcurrentModificationException
        ensure(adapter, ContextHolder.context, false);
        if (pos < 0 || pos >= groups.size()) {
            return;
        }
        Object ourItem = findByUri(groups);
        if (ourItem == null) {
            return;
        }
        final int itemIndex = itemsOf(groups.get(pos)).indexOf(ourItem);
        if (itemIndex < 0) {
            return;
        }
        Object itemView = Reflect.field(holder, "itemView");
        if (!(itemView instanceof View)) {
            return;
        }
        final View inner = findInnerRecycler((View) itemView);
        if (inner == null) {
            return;
        }
        inner.post(new Runnable() {
            @Override
            public void run() {
                hookChildClick(inner, itemIndex);
            }
        });
    }

    private static Object findByUri(List<Object> groups) {
        for (Object group : groups) {
            for (Object item : itemsOf(group)) {
                if (URI.equals(Reflect.field(item, "uri"))) {
                    return item;
                }
            }
        }
        return null;
    }

    private static View findInnerRecycler(View root) {
        ArrayList<View> stack = new ArrayList<>();
        stack.add(root);
        while (!stack.isEmpty()) {
            View view = stack.remove(stack.size() - 1);
            if (isRecycler(view)) {
                return view;
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) {
                    stack.add(group.getChildAt(i));
                }
            }
        }
        return null;
    }

    /**
     * 拿到自己那一格的 ViewHolder，hook 它的 onClick(View)。
     *
     * <p>为什么不直接给 childView 换 OnClickListener：网格 item 会被复用，换了 listener
     * 会污染别的格子。hook holder 的方法则天然只在这一格被点到时才触发，且能先判断 uri。
     */
    private static void hookChildClick(View recycler, int itemIndex) {
        Object holder = null;
        try {
            holder = Reflect.call(recycler, "findViewHolderForAdapterPosition", itemIndex);
        } catch (Throwable ignored) {
            // 拿不到就退回给 child view 挂监听
        }
        if (holder == null) {
            View child = childAt(recycler, itemIndex);
            if (child != null) {
                fallbackClick(child);
            }
            return;
        }
        final Class<?> holderClass = holder.getClass();
        if (!CLICK_HOOKED.add(holderClass)) {
            return;
        }
        Method onClick = null;
        for (Class<?> c = holderClass; c != null && c != Object.class && onClick == null; c = c.getSuperclass()) {
            try {
                onClick = c.getDeclaredMethod("onClick", View.class);
            } catch (Throwable ignored) {
                // 往上找
            }
        }
        if (onClick == null) {
            Object itemView = Reflect.field(holder, "itemView");
            if (itemView instanceof View) {
                fallbackClick((View) itemView);
            }
            return;
        }
        try {
            XposedBridge.hookMethod(onClick, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object item = itemFieldOf(param.thisObject);
                        if (item == null || !URI.equals(Reflect.field(item, "uri"))) {
                            return;
                        }
                        Context context = contextFromHolder(param.thisObject);
                        if (context != null) {
                            SettingsDialog.show(context);
                        }
                        param.setResult(null);
                    } catch (Throwable t) {
                        XLog.e("入口点击处理失败", t);
                    }
                }
            });
            XLog.i("入口点击已接管：" + holderClass.getName());
        } catch (Throwable t) {
            XLog.e("hook 入口点击失败", t);
        }
    }

    /** 兜底：直接给格子挂监听（有复用风险，只在 hook 失败时用）。 */
    private static void fallbackClick(View child) {
        child.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Context context = v.getContext();
                if (context != null) {
                    SettingsDialog.show(context);
                }
            }
        });
    }

    private static View childAt(View recycler, int index) {
        try {
            Object layout = Reflect.call(recycler, "getLayoutManager");
            Object child = Reflect.call(layout, "findViewByPosition", index);
            return child instanceof View ? (View) child : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object itemFieldOf(Object holder) {
        for (Field f : Reflect.fields(holder.getClass())) {
            if (Modifier.isStatic(f.getModifiers())) {
                continue;
            }
            if (!f.getType().getName().endsWith("MenuGroup$Item")) {
                continue;
            }
            try {
                f.setAccessible(true);
                return f.get(holder);
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }

    private static Context contextFromHolder(Object holder) {
        Object itemView = Reflect.field(holder, "itemView");
        if (itemView instanceof View) {
            return ((View) itemView).getContext();
        }
        return null;
    }

    /** 让 handleBind 在 bind 回调里也能拿到 Context。 */
    static final class ContextHolder {
        static Context context;
    }
}
