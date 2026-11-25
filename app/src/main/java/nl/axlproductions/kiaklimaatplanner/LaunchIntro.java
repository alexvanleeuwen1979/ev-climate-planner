package nl.axlproductions.kiaklimaatplanner;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

final class LaunchIntro extends FrameLayout {
    private static final int ICE = 0xffc5f6ff;
    private static final long INTRO_HOLD_MS = 1_300;
    private static final long SHEEN_DURATION_MS = 860;
    private static final long AFTER_SHEEN_MS = 300;
    private ValueAnimator sweep;

    private LaunchIntro(Context context) {
        super(context);
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            new int[]{0xff050a11, 0xff101d2b, 0xff040b12}));
        addView(new PhotoBackdrop(context), new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        LinearLayout brand = new LinearLayout(context);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setGravity(Gravity.CENTER);
        brand.setPadding(dp(24), 0, dp(24), 0);
        brand.setTranslationY(-dp(64));
        addView(brand, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        var logoSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        brand.addView(new Wordmark(context), logoSize);

        TextView subtitle = new TextView(context);
        subtitle.setText("CLIMATE CONTROL");
        subtitle.setTextColor(ICE);
        subtitle.setTextSize(17);
        subtitle.setLetterSpacing(0.18f);
        subtitle.setTextScaleX(1.07f);
        subtitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        subtitle.setGravity(Gravity.CENTER);
        var subtitleSize = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleSize.topMargin = dp(7);
        brand.addView(subtitle, subtitleSize);
    }

    static void showIn(FrameLayout root) {
        if (!ValueAnimator.areAnimatorsEnabled() || root.getChildAt(root.getChildCount() - 1) instanceof LaunchIntro) return;
        LaunchIntro intro = new LaunchIntro(root.getContext());
        Sheen sheen = new Sheen(root.getContext());
        intro.addView(sheen, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        root.addView(intro, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));

        intro.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                intro.getViewTreeObserver().removeOnPreDrawListener(this);
                intro.startSweep(root, sheen);
                return true;
            }
        });
    }

    private void startSweep(FrameLayout root, Sheen sheen) {
        sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setStartDelay(INTRO_HOLD_MS);
        sweep.setDuration(SHEEN_DURATION_MS);
        sweep.setInterpolator(new AccelerateDecelerateInterpolator());
        sweep.addUpdateListener(animation -> sheen.move((float) animation.getAnimatedValue()));
        sweep.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                animate().alpha(0f).setStartDelay(AFTER_SHEEN_MS).setDuration(220)
                    .withEndAction(() -> root.removeView(LaunchIntro.this)).start();
            }
        });
        sweep.start();
    }

    static void removeFrom(FrameLayout root) {
        if (root.getChildAt(root.getChildCount() - 1) instanceof LaunchIntro intro) root.removeView(intro);
    }

    @Override protected void onDetachedFromWindow() {
        if (sweep != null) {
            sweep.removeAllListeners();
            sweep.cancel();
        }
        animate().cancel();
        super.onDetachedFromWindow();
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class PhotoBackdrop extends View {
        private final Bitmap image;
        private final RectF photo = new RectF();
        private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint opacity = new Paint();
        private final Paint fade = new Paint();

        PhotoBackdrop(Context context) {
            super(context);
            image = BitmapFactory.decodeResource(getResources(), R.drawable.climate_artwork);
            opacity.setAlpha(102);
            fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            if (width == 0 || height == 0) return;
            float size = Math.min(width, height * 0.64f);
            float left = (width - size) / 2f;
            photo.set(left, height - size, left + size, height);
            fade.setShader(new LinearGradient(0, photo.top, 0, photo.bottom,
                new int[]{0x00000000, 0xff000000, 0xff000000, 0x00000000},
                new float[]{0f, 0.31f, 0.93f, 1f}, Shader.TileMode.CLAMP));
        }

        @Override protected void onDraw(Canvas canvas) {
            if (photo.isEmpty()) return;
            int layer = canvas.saveLayer(photo, opacity);
            canvas.drawBitmap(image, null, photo, bitmapPaint);
            canvas.drawRect(photo, fade);
            canvas.restoreToCount(layer);
        }
    }

    private static final class Wordmark extends View {
        private final Bitmap logo;
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        // The source is the supplied 1200 x 800 logo, with white margins.
        private final Rect mark = new Rect(46, 268, 1163, 538);
        private final RectF destination = new RectF();

        Wordmark(Context context) {
            super(context);
            logo = BitmapFactory.decodeResource(getResources(), R.drawable.kia_logo_source);
            // Map the navy ink to silver and the white source backdrop to zero alpha.
            paint.setColorFilter(new ColorMatrixColorFilter(new float[]{
                0, 0, 0, 0, 238,
                0, 0, 0, 0, 248,
                0, 0, 0, 0, 255,
                -0.22f, -0.72f, -0.06f, 0, 251
            }));
        }

        @Override protected void onDraw(Canvas canvas) {
            float width = Math.min(getWidth(), 228 * getResources().getDisplayMetrics().density);
            float scale = Math.min(width / mark.width(), (float) getHeight() / mark.height());
            float halfWidth = scale * mark.width() / 2;
            float halfHeight = scale * mark.height() / 2;
            float x = getWidth() / 2f;
            float y = getHeight() / 2f;
            destination.set(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight);
            canvas.drawBitmap(logo, mark, destination, paint);
        }
    }

    private static final class Sheen extends View {
        private final Paint paint = new Paint();
        private final Matrix movement = new Matrix();
        private Shader gradient;
        private float progress;

        Sheen(Context context) {
            super(context);
        }

        void move(float value) {
            progress = value;
            invalidate();
        }

        @Override protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
            if (width == 0 || height == 0) return;
            float band = width * 0.38f;
            gradient = new LinearGradient(-band, -band * 0.22f, band, band * 0.22f,
                new int[]{0x00a8eaff, 0x15a8eaff, 0x54eef8ff, 0x15a8eaff, 0x00a8eaff},
                new float[]{0f, 0.34f, 0.5f, 0.66f, 1f}, Shader.TileMode.CLAMP);
            paint.setShader(gradient);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (gradient == null) return;
            float width = getWidth();
            float margin = width * 0.48f + getHeight() * 0.12f;
            movement.setTranslate(-margin + (width + margin * 2) * progress, getHeight() / 2f);
            gradient.setLocalMatrix(movement);
            canvas.drawRect(0, 0, width, getHeight(), paint);
        }
    }
}
