package ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import emu.Control;
import emu.KeyCode;

/**
 * Single touch zone divided into 3 vertical bands for rapid thumb-sliding.
 * Top = JUMP (up), Middle = TRAVERSE (right), Bottom = SLIDE (down).
 * Sliding between zones switches action instantly without lifting.
 */
public class ActionStripView extends View {

    private static final int ZONE_NONE = -1;
    private static final int ZONE_JUMP = 0;
    private static final int ZONE_TRAVERSE = 1;
    private static final int ZONE_SLIDE = 2;

    private static final int[] ZONE_FLAGS = {
            KeyCode.C64STICK_UP,    // JUMP
            KeyCode.C64STICK_RIGHT, // TRAVERSE
            KeyCode.C64STICK_DOWN   // SLIDE
    };

    private static final String[] ZONE_LABELS = {
            "\u2B06",  // up arrow
            "\u27A1",  // right arrow
            "\u2B07"   // down arrow
    };

    private static final String[] ZONE_NAMES = {
            "SAUTER",
            "TRAVERSER",
            "GLISSER"
    };

    // Colors: normal / pressed (ARGB)
    private static final int[][] ZONE_COLORS = {
            {0x6640A040, 0xCC66CC66}, // green
            {0x66884098, 0xCCCC66FF}, // purple
            {0x666060C0, 0xCC6666FF}, // blue
    };

    private static final int[][] ZONE_BORDER_COLORS = {
            {0x6678CC78, 0xFFAAFFAA}, // green border
            {0x66AA78CC, 0xFFDDAAFF}, // purple border
            {0x667878FF, 0xFFAAAAFF}, // blue border
    };

    private int activeZone = ZONE_NONE;

    private Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint namePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF rect = new RectF();

    public ActionStripView(Context context, AttributeSet attrs) {
        super(context, attrs);

        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(3f);

        iconPaint.setTextAlign(Paint.Align.CENTER);
        iconPaint.setColor(0xFFFFFFFF);

        namePaint.setTextAlign(Paint.Align.CENTER);
        namePaint.setColor(0xAAFFFFFF);

        dividerPaint.setColor(0x44FFFFFF);
        dividerPaint.setStrokeWidth(1f);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                int zone = getZoneForY(event.getY());
                if (zone != activeZone) {
                    setActiveZone(zone);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                setActiveZone(ZONE_NONE);
                return true;
        }
        return false;
    }

    private int getZoneForY(float y) {
        float h = getHeight();
        if (h <= 0) return ZONE_NONE;
        float ratio = y / h;
        if (ratio < 0.333f) return ZONE_JUMP;
        if (ratio < 0.666f) return ZONE_TRAVERSE;
        return ZONE_SLIDE;
    }

    private void setActiveZone(int zone) {
        int oldZone = activeZone;
        activeZone = zone;

        Control control = Control.instance();
        if (control == null) return;

        // Clear old zone flag
        if (oldZone >= 0 && oldZone < ZONE_FLAGS.length) {
            control.clearStickFlag(ZONE_FLAGS[oldZone]);
        }
        // Set new zone flag
        if (zone >= 0 && zone < ZONE_FLAGS.length) {
            control.setStickFlag(ZONE_FLAGS[zone]);
        }

        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        float zoneH = h / 3f;
        float cornerRadius = 14f;
        float iconSize = Math.min(w * 0.4f, zoneH * 0.35f);
        float nameSize = Math.min(w * 0.13f, zoneH * 0.12f);

        for (int i = 0; i < 3; i++) {
            float top = i * zoneH;
            float bottom = top + zoneH;
            boolean active = (i == activeZone);

            // Fill
            fillPaint.setColor(ZONE_COLORS[i][active ? 1 : 0]);
            rect.set(4, top + 2, w - 4, bottom - 2);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, fillPaint);

            // Border
            borderPaint.setColor(ZONE_BORDER_COLORS[i][active ? 1 : 0]);
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint);

            // Icon
            iconPaint.setTextSize(iconSize);
            iconPaint.setAlpha(active ? 255 : 180);
            float iconY = top + zoneH * 0.5f + iconSize * 0.3f;
            canvas.drawText(ZONE_LABELS[i], w / 2f, iconY - nameSize, iconPaint);

            // Name
            namePaint.setTextSize(nameSize);
            namePaint.setAlpha(active ? 220 : 120);
            canvas.drawText(ZONE_NAMES[i], w / 2f, iconY + nameSize * 1.5f, namePaint);
        }
    }
}
