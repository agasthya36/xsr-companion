package com.xsr.companion.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.xsr.companion.data.RideDatabaseHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight speed-over-time chart. Deliberately avoids a charting library:
 * it only needs one polyline, so drawing it directly keeps the APK and runtime
 * cost minimal.
 */
public class RideChartView extends View {

    private final List<RideDatabaseHelper.RidePoint> points = new ArrayList<>();

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public RideChartView(Context context) {
        super(context);
        init(context);
    }

    public RideChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public RideChartView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        float density = context.getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.4f * density);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setColor(0xFF4D8DFF);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0x334D8DFF);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1f * density);
        gridPaint.setColor(0xFF2C3748);

        textPaint.setColor(0xFF6C7A90);
        textPaint.setTextSize(10f * density);
    }

    public void setPoints(List<RideDatabaseHelper.RidePoint> newPoints) {
        points.clear();
        if (newPoints != null) points.addAll(newPoints);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float padLeft = getPaddingLeft() + 2f;
        float padRight = getPaddingRight() + 2f;
        float padTop = getPaddingTop() + 14f;
        float padBottom = getPaddingBottom() + 16f;
        float plotW = width - padLeft - padRight;
        float plotH = height - padTop - padBottom;
        if (plotW <= 0 || plotH <= 0) return;

        canvas.drawLine(padLeft, padTop + plotH, padLeft + plotW, padTop + plotH, gridPaint);
        canvas.drawLine(padLeft, padTop + plotH / 2f, padLeft + plotW, padTop + plotH / 2f, gridPaint);

        if (points.size() < 2) {
            textPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Not enough GPS samples for a chart",
                    padLeft + plotW / 2f, padTop + plotH / 2f - 6f, textPaint);
            return;
        }

        long minTime = Long.MAX_VALUE;
        long maxTime = Long.MIN_VALUE;
        int maxSpeed = 0;
        for (RideDatabaseHelper.RidePoint p : points) {
            minTime = Math.min(minTime, p.timestamp);
            maxTime = Math.max(maxTime, p.timestamp);
            maxSpeed = Math.max(maxSpeed, p.speedKmh);
        }
        if (maxTime <= minTime) maxTime = minTime + 1000L;
        int axisMax = Math.max(20, ((maxSpeed + 9) / 10) * 10);

        linePath.reset();
        boolean started = false;
        float lastX = padLeft;
        float lastY = padTop + plotH;
        for (RideDatabaseHelper.RidePoint p : points) {
            float x = padLeft + plotW * (p.timestamp - minTime) / (float) (maxTime - minTime);
            float y = padTop + plotH - plotH * (Math.min(p.speedKmh, axisMax) / (float) axisMax);
            if (!started) {
                linePath.moveTo(x, y);
                started = true;
            } else {
                linePath.lineTo(x, y);
            }
            lastX = x;
            lastY = y;
        }

        fillPath.set(linePath);
        fillPath.lineTo(lastX, padTop + plotH);
        fillPath.lineTo(padLeft, padTop + plotH);
        fillPath.close();

        fillPaint.setShader(new LinearGradient(0, padTop, 0, padTop + plotH,
                0x554D8DFF, 0x004D8DFF, Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fillPaint);
        canvas.drawPath(linePath, linePaint);

        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(axisMax + " km/h", padLeft + 2f, padTop - 3f, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(formatTime(maxTime - minTime), padLeft + plotW, height - 3f, textPaint);
    }

    private String formatTime(long millis) {
        long secs = Math.max(0, millis / 1000);
        long mins = secs / 60;
        if (mins >= 60) {
            return String.format(java.util.Locale.getDefault(), "%dh %02dm", mins / 60, mins % 60);
        }
        return String.format(java.util.Locale.getDefault(), "%dm %02ds", mins, secs % 60);
    }
}
