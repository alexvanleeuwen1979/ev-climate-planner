package nl.axlproductions.kiaklimaatplanner;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

final class GlassSheen extends Drawable {
    private final boolean panel;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final int[] GLOW_WIDTHS = {32, 18, 9, 4};
    private final Path light = new Path();

    GlassSheen(boolean panel) {
        this.panel = panel;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override public void draw(Canvas canvas) {
        var bounds = getBounds();
        float w = bounds.width();
        float h = bounds.height();
        if (w <= 0 || h <= 0) return;
        int saved = canvas.save();
        canvas.translate(bounds.left, bounds.top);
        if (panel) canvas.clipRect(w * .32f, h * .06f, w * .98f, h * .68f);
        light.reset();
        if (panel) {
            light.moveTo(w * .58f, h * .44f);
            light.cubicTo(w * .82f, h * .40f, w * .90f, h * .19f, w * .97f, h * .04f);
        } else {
            light.moveTo(w * .06f, h * .94f);
            light.cubicTo(w * .23f, h * .89f, w * .45f, h * .91f, w * .79f, h * .79f);
        }
        float scale = w / 420f;
        paint.setShader(new LinearGradient(w * (panel ? .46f : .06f), 0, w * (panel ? .96f : .79f), 0,
            new int[]{0x0026b4f0, 0xb234c4fc, 0x0026b4f0}, null, Shader.TileMode.CLAMP));
        for (int width : GLOW_WIDTHS) {
            paint.setAlpha(panel ? 46 : 37);
            paint.setStrokeWidth(width * scale);
            canvas.drawPath(light, paint);
        }
        paint.setAlpha(210);
        paint.setShader(new LinearGradient(w * (panel ? .46f : .06f), 0, w * (panel ? .96f : .79f), 0,
            new int[]{0x0026b4f0, 0xe091edff, 0x0026b4f0}, null, Shader.TileMode.CLAMP));
        paint.setStrokeWidth(1.3f * scale);
        canvas.drawPath(light, paint);
        canvas.restoreToCount(saved);
        if (panel) drawLowerEdge(canvas, bounds);
    }

    private void drawLowerEdge(Canvas canvas, android.graphics.Rect bounds) {
        float left = bounds.left + bounds.width() * .25f;
        float right = bounds.right - bounds.width() * .045f;
        float y = bounds.bottom - bounds.width() / 550f;
        paint.setAlpha(200);
        paint.setShader(new LinearGradient(left, 0, right, 0,
            new int[]{0x0033a7df, 0x6c5edcff, 0x0033a7df}, null, Shader.TileMode.CLAMP));
        paint.setStrokeWidth(bounds.width() / 370f);
        canvas.drawLine(left, y, right, y, paint);
    }

    @Override public void setAlpha(int alpha) {}
    @Override public void setColorFilter(ColorFilter filter) {}
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
