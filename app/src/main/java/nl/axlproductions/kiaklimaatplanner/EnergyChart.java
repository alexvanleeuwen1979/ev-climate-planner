package nl.axlproductions.kiaklimaatplanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

final class EnergyChart extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<EnergyHistory.Point> points;
    private final LocalDate date;
    private final ZoneId zone;
    private final int accent;
    private final boolean battery;
    private final float maximum;

    EnergyChart(Context context, List<EnergyHistory.Point> points, LocalDate date, ZoneId zone, boolean battery) {
        super(context);
        this.points = points;
        this.date = date;
        this.zone = zone;
        this.battery = battery;
        maximum = battery ? 100 : (float) Math.max(1, Math.ceil(points.isEmpty() ? 0 : points.get(points.size() - 1).value()));
        accent = battery ? 0xff54d5ea : 0xffffae48;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(44), top = dp(10), right = Math.max(left + dp(20), getWidth() - dp(12));
        float bottom = Math.max(top + dp(20), getHeight() - dp(26));
        paint.setTextSize(dp(11));
        paint.setStrokeWidth(dp(1));
        paint.setStyle(Paint.Style.FILL);
        for (int row = 0; row <= 4; row++) {
            float y = bottom - (bottom - top) * row / 4;
            paint.setColor(0x4078a2af);
            canvas.drawLine(left, y, right, y, paint);
            if (row % 2 == 0) {
                paint.setColor(0xffc9dee2);
                double value = maximum * row / 4;
                canvas.drawText(battery ? ((int) value) + "%" : format(value), 0, y + dp(4), paint);
            }
        }
        long from = date.atStartOfDay(zone).toInstant().toEpochMilli();
        long until = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (int hour : new int[]{0, 6, 12, 18, 24}) {
            long time = hour == 24 ? until : date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli();
            float x = x(time, from, until, left, right);
            paint.setColor(0x3078a2af);
            canvas.drawLine(x, top, x, bottom, paint);
            paint.setColor(0xffc9dee2);
            String label = String.format(Locale.ROOT, "%02d", hour);
            canvas.drawText(label, Math.min(right - paint.measureText(label), x - paint.measureText(label) / 2),
                bottom + dp(19), paint);
        }

        var path = new Path();
        for (int index = 0; index < points.size(); index++) {
            var point = points.get(index);
            float x = x(point.time(), from, until, left, right);
            float y = bottom - (bottom - top) * (float) point.value() / maximum;
            if (index == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        paint.setColor(accent);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(dp(2));
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        if (battery) {
            for (var point : points) {
                canvas.drawCircle(x(point.time(), from, until, left, right),
                    bottom - (bottom - top) * (float) point.value() / maximum, dp(2.5f), paint);
            }
        }
    }

    private static float x(long time, long from, long until, float left, float right) {
        return left + (right - left) * (Math.max(from, Math.min(until, time)) - from) / (until - from);
    }

    static String format(double value) {
        return String.format(AppLanguage.locale(), value == Math.rint(value) ? "%.0f" : "%.2f", value);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
