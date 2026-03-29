package ui;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import emu.Control;
import util.LogManager;
import util.Logger;

public class EmuView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private final static Logger logger = LogManager.getLogger(EmuView.class.getName());

    private volatile boolean renderingEnabled;
    private SurfaceHolder surfaceHolder;
    private Thread renderThread;

    private Paint bitmapPaint;
    private Bitmap bitmap;
    private boolean bitmapReady;

    private Rect renderRect = new Rect(0, 0, 0, 0);
    private Rect bitmapSourceRect = new Rect(0, 0, Control.DISPLAY_X, Control.DISPLAY_Y);

    private Paint backgroundPaint;

    private volatile boolean viewDestroyed;

    public EmuView(Context context, AttributeSet attrs) {
        super(context, attrs);

        surfaceHolder = getHolder();
        surfaceHolder.addCallback(this);

        bitmap = Bitmap.createBitmap(Control.DISPLAY_X, Control.DISPLAY_Y, Bitmap.Config.ARGB_8888);

        bitmapPaint = new Paint();
        bitmapPaint.setAntiAlias(false);
        bitmapPaint.setFilterBitmap(false);
        bitmapPaint.setDither(false);

        backgroundPaint = new Paint();
        backgroundPaint.setAntiAlias(false);
        backgroundPaint.setARGB(255, 0, 0, 0);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        viewDestroyed = false;
        startRendering();
        Control ctrl = Control.instance();
        if (ctrl != null) {
            ctrl.resume();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        viewDestroyed = true;
        Control ctrl = Control.instance();
        if (ctrl != null) {
            ctrl.pause();
        }
        stopRendering();
    }

    private void startRendering() {
        renderingEnabled = true;
        renderThread = new Thread(this);
        renderThread.start();
    }

    private void stopRendering() {
        renderingEnabled = false;
        if (null != renderThread) {
            renderThread.interrupt();
            try {
                renderThread.join(1000);
            } catch (InterruptedException e) {
                ;
            }
            renderThread = null;
        }
    }

    @Override
    public void run() {
        Canvas canvas;

        while (!Thread.interrupted() && !viewDestroyed) {
            if (renderingEnabled) {
                try {
                    canvas = surfaceHolder.lockCanvas(null);
                    if (null != canvas) {
                        doRendering(canvas);
                        surfaceHolder.unlockCanvasAndPost(canvas);
                    }
                } catch (Exception e) {
                    // Surface may have been destroyed
                    break;
                }
            }
        }
    }

    private void updateBitmap() {
        Control ctrl = Control.instance();
        if (ctrl == null) return;

        byte[] rawData = ctrl.lockTextureData();
        if (null == rawData) {
            return;
        }

        ByteBuffer buffer = ByteBuffer.wrap(rawData);
        buffer.order(ByteOrder.nativeOrder());
        buffer.position(0);
        bitmap.copyPixelsFromBuffer(buffer);
        bitmapReady = true;

        ctrl.unlockTextureData();
    }

    private void doRendering(Canvas canvas) {
        try {
            updateBitmap();
        } catch (Exception e) {
            logger.error("updateBitmap: exception: " + e);
            return;
        }

        if (bitmapReady) {
            renderRect.set(matchSize(canvas.getWidth(), canvas.getHeight()));

            if (renderRect.top > 0) {
                canvas.drawRect(0.0f, 0.0f, renderRect.right, renderRect.top - 1, backgroundPaint);
            }

            if (renderRect.bottom < canvas.getHeight()) {
                canvas.drawRect(0.0f, renderRect.bottom, renderRect.right, canvas.getHeight(), backgroundPaint);
            }

            canvas.drawBitmap(bitmap, bitmapSourceRect, renderRect, bitmapPaint);
        }
    }

    private static Rect matchSize(int width, int height) {
        int zoomFactor = Math.min(width / (Control.DISPLAY_X - 44),
                height / (Control.DISPLAY_Y - 80));
        if (zoomFactor < 1) zoomFactor = 1;
        else if (zoomFactor > 32) zoomFactor = 32;

        int outW = Control.DISPLAY_X * zoomFactor;
        int outH = Control.DISPLAY_Y * zoomFactor;
        int outX = (width - outW) / 2;
        int outY = (height - outH) / 2;

        return new Rect(outX, outY, outX + outW, outY + outH);
    }
}
