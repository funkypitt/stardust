package ui;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import emu.Control;
import emu.KeyCode;

public class StardustActivity extends Activity {

    private Control control;
    private int currentStickMask = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersive
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(getResources().getIdentifier("activity_stardust", "layout", getPackageName()));

        hideSystemUI();

        // Initialize emulator
        control = new Control();
        control.init();
        control.start();

        // Auto-start game
        control.autoStartGame(this);

        // Setup buttons
        setupButton(findViewById(getResources().getIdentifier("btn_glisser", "id", getPackageName())), KeyCode.C64STICK_DOWN);
        setupButton(findViewById(getResources().getIdentifier("btn_sauter", "id", getPackageName())), KeyCode.C64STICK_UP);
        setupButton(findViewById(getResources().getIdentifier("btn_traverser", "id", getPackageName())), KeyCode.C64STICK_RIGHT);
        setupButton(findViewById(getResources().getIdentifier("btn_feu", "id", getPackageName())), KeyCode.C64STICK_FIRE);
    }

    private void setupButton(View button, final int stickFlag) {
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        currentStickMask |= stickFlag;
                        control.setStick(currentStickMask);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        currentStickMask &= ~stickFlag;
                        control.setStick(currentStickMask);
                        return true;
                }
                return false;
            }
        });
    }

    private void hideSystemUI() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
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
