package com.tvdash.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Simple horizontal bars. No animation, so it stays cheap on a Fire TV stick. */
final class WorkBars extends View {
    static final class Row {
        final String label;
        final int value;
        Row(String label, int value) {
            this.label = label == null ? "" : label;
            this.value = value;
        }
    }

    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint count = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Row> rows = new ArrayList<Row>();

    WorkBars(Context context) {
        super(context);
        label.setColor(Vigo.TEXT);
        label.setTypeface(Vigo.dm(context));
        label.setTextSize(sp(14));
        count.setColor(Vigo.AQUA);
        count.setTypeface(Vigo.manrope(context));
        count.setTextSize(sp(14));
        count.setTextAlign(Paint.Align.RIGHT);
        bar.setColor(Vigo.TEAL);
    }

    void setRows(List<Row> next, int color) {
        rows = next == null ? new ArrayList<Row>() : next;
        bar.setColor(color);
        requestLayout();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0) width = Ui.dp(getContext(), 400);
        int height = Ui.dp(getContext(), 8) + Math.max(1, rows.size()) * Ui.dp(getContext(), 32);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int max = 1;
        for (int i = 0; i < rows.size(); i++) if (rows.get(i).value > max) max = rows.get(i).value;
        float labelW = Ui.dp(getContext(), 168);
        float countW = Ui.dp(getContext(), 48);
        float left = labelW;
        float right = getWidth() - countW;
        float rowH = Ui.dp(getContext(), 32);
        float barH = Ui.dp(getContext(), 10);
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            float y = i * rowH + Ui.dp(getContext(), 18);
            String name = row.label;
            if (label.measureText(name) > labelW - Ui.dp(getContext(), 12)) {
                while (name.length() > 1 && label.measureText(name + "…") > labelW - Ui.dp(getContext(), 12)) {
                    name = name.substring(0, name.length() - 1);
                }
                name = name + "…";
            }
            canvas.drawText(name, 0, y, label);
            float bw = (right - left) * (row.value / (float) max);
            float top = y - barH + Ui.dp(getContext(), 2);
            canvas.drawRoundRect(left, top, left + Math.max(bw, Ui.dp(getContext(), 2)), top + barH,
                    Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), bar);
            canvas.drawText(String.valueOf(row.value), getWidth() - Ui.dp(getContext(), 4), y, count);
        }
    }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
