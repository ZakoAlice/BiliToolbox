package io.github.zakoalice.bilitoolbox;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一组「失败就返回 null / 什么都不做」的反射工具。
 *
 * <p>这个模块要活在哔哩哔哩好几个版本上，类名、字段名随时会变。所有反射都走这里，
 * 出问题时只在日志里留一行，不往宿主抛异常 —— 一个折腾下载按钮的模块，不该让主 App 崩掉。
 */
public final class Reflect {

    private Reflect() {
    }

    public static Class<?> findClass(String name, ClassLoader cl) {
        if (name == null) {
            return null;
        }
        try {
            return Class.forName(name, false, cl);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Object newInstance(Class<?> type) {
        if (type == null) {
            return null;
        }
        try {
            Constructor<?> ctor = type.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 含父类在内的全部实例字段。 */
    public static List<Field> fields(Class<?> start) {
        List<Field> out = new ArrayList<>();
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            Collections.addAll(out, c.getDeclaredFields());
        }
        return out;
    }

    public static List<Method> methods(Class<?> start) {
        List<Method> out = new ArrayList<>();
        for (Class<?> c = start; c != null && c != Object.class; c = c.getSuperclass()) {
            Collections.addAll(out, c.getDeclaredMethods());
        }
        return out;
    }

    public static Object field(Object target, String name) {
        if (target == null) {
            return null;
        }
        for (Field f : fields(target.getClass())) {
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

    public static boolean setField(Object target, String name, Object value) {
        if (target == null) {
            return false;
        }
        for (Field f : fields(target.getClass())) {
            if (!f.getName().equals(name)) {
                continue;
            }
            return setFieldValue(f, target, value);
        }
        return false;
    }

    /** 按候选名依次尝试（B 站字段常被 R8 改名，例如 id 变成 f267193id）。 */
    public static boolean setField(Object target, String[] names, Object value) {
        for (String name : names) {
            if (setField(target, name, value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean setFieldValue(Field f, Object target, Object value) {
        try {
            f.setAccessible(true);
            Class<?> type = f.getType();
            if (type == long.class && value instanceof Number) {
                f.setLong(target, ((Number) value).longValue());
            } else if (type == int.class && value instanceof Number) {
                f.setInt(target, ((Number) value).intValue());
            } else if (type == boolean.class && value instanceof Boolean) {
                f.setBoolean(target, (Boolean) value);
            } else if (!type.isPrimitive()) {
                f.set(target, value);
            } else {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static Object call(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        for (Method m : methods(target.getClass())) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) {
                continue;
            }
            try {
                m.setAccessible(true);
                return m.invoke(target, args);
            } catch (Throwable ignored) {
                // 试下一个同名重载
            }
        }
        return null;
    }

    /** 第一个无参、返回类型符合预期的方法（HD 的菜单列表藏在 getter 后面）。 */
    public static Object callGetter(Object target, Class<?> returnType) {
        if (target == null) {
            return null;
        }
        for (Method m : methods(target.getClass())) {
            if (Modifier.isStatic(m.getModifiers())
                    || m.getParameterTypes().length != 0
                    || !returnType.isAssignableFrom(m.getReturnType())) {
                continue;
            }
            try {
                m.setAccessible(true);
                return m.invoke(target);
            } catch (Throwable ignored) {
                // 试下一个
            }
        }
        return null;
    }

    public static Object callGetter(Object target, String name) {
        return call(target, name);
    }

    /** 取对象里第一个类型匹配的字段值，用于「不知道字段叫啥只知道类型」的场景。 */
    public static Object fieldOfType(Object target, Class<?> type) {
        if (target == null) {
            return null;
        }
        for (Field f : fields(target.getClass())) {
            if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) {
                continue;
            }
            try {
                f.setAccessible(true);
                Object v = f.get(target);
                if (v != null) {
                    return v;
                }
            } catch (Throwable ignored) {
                // 继续
            }
        }
        return null;
    }
}
