package ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;

import com.stardust.app.R;
import emu.Control;
import emu.KeyCode;

import java.io.PrintWriter;
import java.io.StringWriter;

public class StardustActivity extends Activity {

    private Control control;
    private static TextView debugOverlay;
    private static final StringBuilder debugLog = new StringBuilder();
    private static Handler uiHandler;

    public static void log(String msg) {
        debugLog.append(msg).append("\n");
        if (uiHandler != null && debugOverlay != null) {
            uiHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (debugOverlay != null) {
                        debugOverlay.setText(debugLog.toString());
                        // Auto-scroll to bottom
                        View parent = (View) debugOverlay.getParent();
                        if (parent instanceof ScrollView) {
                            ((ScrollView) parent).fullScroll(View.FOCUS_DOWN);
                        }
                    }
                }
            });
        }
    }

    private static void logError(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        log("ERROR: " + msg + "\n" + sw.toString());
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        uiHandler = new Handler(Looper.getMainLooper());

        // Catch uncaught exceptions and show on screen
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                final String threadName = t.getName();
                final StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                final String trace = sw.toString();

                try {
                    uiHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            showFatalError("UNCAUGHT on [" + threadName + "]:\n" + trace);
                        }
                    });
                    // Give UI thread time to display
                    Thread.sleep(60000);
                } catch (Exception ignored) {
                }
            }
        });

        try {
            log("onCreate start");

            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

            log("creating Control...");
            control = new Control();
            control.init();
            log("Control created OK");

            log("inflating layout...");
            setContentView(R.layout.activity_stardust);
            log("layout inflated OK");

            hideSystemUI();

            log("starting emulator...");
            control.start();
            log("emulator started OK");

            log("auto-starting game...");
            control.autoStartGame(this);
            log("autoStartGame queued");

            // Fire button
            View fireBtn = findViewById(R.id.btn_feu);
            if (fireBtn == null) {
                log("WARNING: btn_feu not found!");
            } else {
                fireBtn.setOnTouchListener(new View.OnTouchListener() {
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                control.setStickFlag(KeyCode.C64STICK_FIRE);
                                return true;
                            case MotionEvent.ACTION_UP:
                            case MotionEvent.ACTION_CANCEL:
                                control.clearStickFlag(KeyCode.C64STICK_FIRE);
                                return true;
                        }
                        return false;
                    }
                });
                log("fire button OK");
            }

            log("onCreate done");

        } catch (Throwable t) {
            logError("CRASH in onCreate", t);
            showFatalError("CRASH in onCreate:\n" + t.toString());
        }
    }

    private void showFatalError(String message) {
        try {
            TextView tv = new TextView(this);
            tv.setText(message);
            tv.setTextColor(Color.RED);
            tv.setBackgroundColor(Color.BLACK);
            tv.setTextSize(11f);
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setPadding(20, 20, 20, 20);
            tv.setGravity(Gravity.TOP | Gravity.LEFT);

            ScrollView scroll = new ScrollView(this);
            scroll.addView(tv);
            setContentView(scroll);
        } catch (Exception e) {
            // Last resort
        }
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (control != null) {
            control.stop();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (control != null) {
            control.stop();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }
}
