package nl.axlproductions.kiaklimaatplanner;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

final class ClimateTimeline extends View {
    private static final int TRACK = 0xff4b8bac;
    private static final int MARK = 0xffb1d6e7;
    private static final int AMBER = 0xffffb747;
    private static final String CLOCK_PATTERN = "HH:mm";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final float textSize;
    private long start, end, now;
    private String startLabel = "", endLabel = "";

    ClimateTimeline(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP,
            14, getResources().getDisplayMetrics());
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    void update(long start, long end, long now) {
        if (this.start != start || this.end != end) {
            startLabel = time(start);
            endLabel = time(end);
            setContentDescription("Tijdlijn van " + startLabel + " tot " + endLabel
                + ", met richtpunten om de 15 minuten");
        }
        this.start = start;
        this.end = end;
        this.now = now;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (end <= start) return;
        paint.setTextSize(textSize);
        float left = Math.max(8 * density, paint.measureText(startLabel) / 2);
        float right = getWidth() - Math.max(8 * density, paint.measureText(endLabel) / 2);
        if (right <= left) return;
        float trackY = 17 * density;
        paint.setColor(TRACK);
        paint.setStrokeWidth(4.5f * density);
        canvas.drawLine(left, trackY, right, trackY, paint);

        float current = position(now, left, right);
        if (current > left) {
            paint.setColor(AMBER);
            paint.setStrokeWidth(8 * density);
            canvas.drawLine(left, trackY, current, trackY, paint);
        }
        marker(canvas, left, trackY, startLabel, now >= start);
        marker(canvas, right, trackY, endLabel, now >= end);
        float previousLabel = left;
        float labelGap = paint.measureText("00:00") + 8 * density;
        for (long anchor = start + Schedule.MONITORED_INTERVAL; anchor < end;
                anchor += Schedule.MONITORED_INTERVAL) {
            float x = position(anchor, left, right);
            if (right - x < 7 * density) break;
            boolean showLabel = x - previousLabel >= labelGap && right - x >= labelGap;
            marker(canvas, x, trackY, showLabel ? time(anchor) : "", anchor <= now);
            if (showLabel) previousLabel = x;
        }
        paint.setColor(now >= start ? AMBER : MARK);
        canvas.drawCircle(current, trackY, 6 * density, paint);
        paint.setColor(0xff122735);
        canvas.drawCircle(current, trackY, 3.25f * density, paint);
    }

    private void marker(Canvas canvas, float x, float y, String label, boolean elapsed) {
        paint.setColor(elapsed ? AMBER : MARK);
        canvas.drawCircle(x, y, 3 * density, paint);
        paint.setColor(0xff14344a);
        canvas.drawCircle(x, y, 1.5f * density, paint);
        paint.setColor(0xffedf7fc);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(label, x, y + 9 * density - paint.ascent(), paint);
    }

    private float position(long time, float left, float right) {
        float fraction = (float) (time - start) / (end - start);
        return left + (right - left) * Math.max(0, Math.min(1, fraction));
    }

    private static String time(long millis) {
        return DateTimeFormatter.ofPattern(CLOCK_PATTERN).withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(millis));
    }
}
