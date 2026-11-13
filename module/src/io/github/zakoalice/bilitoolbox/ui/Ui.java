package io.github.zakoalice.bilitoolbox.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 用代码搭界面的小工具集。
 *
 * <p>模块不打包任何 layout / drawable 资源 —— 资源 id 会和宿主撞车，是 Xposed 模块最常见的
 * 崩溃来源之一。所有界面都在运行时用宿主 Context 现搭，颜色固定成哔哩哔哩的粉 + 中性灰，
 * 不跟随宿主换肤，这样在深浅色模式下都可读。
 */
public final class Ui {

    /** 哔哩哔哩品牌粉。 */
    public static final int PINK = 0xFFFB7299;
    public static final int PINK_DARK = 0xFFE45C82;
    public static final int BG = 0xFFF4F4F6;
    public static final int CARD = 0xFFFFFFFF;
    public static final int TEXT = 0xFF18191C;
    public static final int TEXT_SUB = 0xFF9499A0;
    public static final int DIVIDER = 0xFFEEEFF1;

    private Ui() {
    }

    public static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static GradientDrawable roundRect(Context context, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(context, radiusDp));
        return d;
    }

    public static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    public static LinearLayout row(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(android.view.Gravity.CENTER_VERTICAL);
        return layout;
    }

    public static TextView text(Context context, String content, float sizeSp, int color) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    public static TextView bold(Context context, String content, float sizeSp, int color) {
        TextView view = text(context, content, sizeSp, color);
        view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        return view;
    }

    /** 一张白色圆角卡片，内部纵向排列。 */
    public static LinearLayout card(Context context) {
        LinearLayout layout = column(context);
        layout.setBackground(roundRect(context, CARD, 12f));
        layout.setClipToOutline(true);
        return layout;
    }

    public static View divider(Context context) {
        View view = new View(context);
        view.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.5f)));
        lp.leftMargin = dp(context, 16);
        view.setLayoutParams(lp);
        return view;
    }

    /** 分组小标题（浅灰、比正文小一号）。 */
    public static TextView sectionTitle(Context context, String content) {
        TextView view = text(context, content, 12.5f, TEXT_SUB);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(context, 22);
        lp.rightMargin = dp(context, 22);
        lp.topMargin = dp(context, 18);
        lp.bottomMargin = dp(context, 6);
        view.setLayoutParams(lp);
        return view;
    }

    /**
     * 一行设置项：主标题 + 可选副标题，右侧留一个 View 挂开关或箭头。
     *
     * @param tail 右侧控件，可为 null
     */
    public static LinearLayout item(Context context, String title, String subtitle, View tail) {
        LinearLayout row = row(context);
        // LinearLayout 里 addView 出来的子 View 默认 WRAP_CONTENT，不显式撑满会缩成一小团
        row.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14));

        LinearLayout texts = column(context);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        texts.setLayoutParams(textLp);
        TextView titleView = text(context, title, 15f, TEXT);
        texts.addView(titleView);
        if (subtitle != null && subtitle.length() > 0) {
            TextView sub = text(context, subtitle, 12f, TEXT_SUB);
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = dp(context, 3);
            sub.setLayoutParams(subLp);
            texts.addView(sub);
        }
        row.addView(texts);

        if (tail != null) {
            LinearLayout.LayoutParams tailLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tailLp.leftMargin = dp(context, 10);
            tail.setLayoutParams(tailLp);
            row.addView(tail);
        }
        return row;
    }

    /** B 站粉色的实心按钮。 */
    public static TextView button(Context context, String label) {
        TextView view = text(context, label, 14f, Color.WHITE);
        view.setGravity(android.view.Gravity.CENTER);
        view.setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10));
        view.setBackground(roundRect(context, PINK, 8f));
        view.setClickable(true);
        return view;
    }

    /** 副标题用的浅色胶囊（放版本号、状态之类的短文本）。 */
    public static TextView chip(Context context, String label, int bg, int fg) {
        TextView view = text(context, label, 11f, fg);
        view.setPadding(dp(context, 8), dp(context, 3), dp(context, 8), dp(context, 3));
        view.setBackground(roundRect(context, bg, 20f));
        return view;
    }

    /** 给可点击的 View 加一个通用的按下反馈（不依赖主题的 ripple）。 */
    public static void clickable(View view, final View.OnClickListener listener) {
        view.setClickable(true);
        view.setOnClickListener(listener);
    }
}
