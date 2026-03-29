package ui;

import android.app.Activity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.stardust.app.R;
import emu.Control;
import emu.KeyCode;

public class StardustActivity extends Activity {

    private Control control;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize emulator BEFORE inflating layout
        // (EmuView surface callbacks call Control.instance())
        control = new Control();
        control.init();

        setContentView(R.layout.activity_stardust);

        hideSystemUI();

        // Start emulator after layout is set
        control.start();

        // Auto-start game
        control.autoStartGame(this);

        // Fire button (left side)
        findViewById(R.id.btn_feu).setOnTouchListener(new View.OnTouchListener() {
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
