package emu;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import system.AudioControl;
import util.LogManager;
import util.Logger;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class Control {

    private final static Logger logger = LogManager.getLogger(Control.class.getName());

    public static final int FRAMES_PER_SECOND = 50;
    private static final int NORMAL_SLEEP_TIME = 1000 / FRAMES_PER_SECOND;
    private static final int PAUSE_SLEEP_TIME = 100;

    public static final int DISPLAY_X = 0x180;
    public static final int DISPLAY_Y = 0x110;
    private static final int DISPLAY_PIXELS = DISPLAY_X * DISPLAY_Y;
    private static final int NUM_VIDEO_BUFFERS = 2;

    private static final int FLAG_UNPACK_GRAPHICS = 0x1;
    private static final int FLAG_USE_GAMMA_CORRECTION = 0x2;

    private Thread emuThread;

    private final Object emuLock = new Object();
    private volatile NativeInterface emu;

    private Object[] videoBuffers = new Object[NUM_VIDEO_BUFFERS];
    private int videoBufferIndex;
    private volatile boolean textureBufferFilled;
    private int textureBufferIndex;

    private int keyboardInputDelay;
    private Queue<Integer> keyboardInputQueue = new ConcurrentLinkedQueue<Integer>();

    private volatile boolean running;
    private volatile boolean paused;

    private int stickMask = 0x0;

    private static volatile Control globalInstance = null;

    private AudioControl audioControl = new AudioControl();

    // Pending disk load from assets
    private volatile byte[] pendingDiskData;
    private volatile String pendingDiskFilename;

    public static Control instance() {
        return globalInstance;
    }

    public Control() {
        globalInstance = this;
    }

    public void init() {
        int videoBufferSize = DISPLAY_PIXELS * 4;

        for (int i = 0; i < NUM_VIDEO_BUFFERS; i++) {
            videoBuffers[i] = new byte[videoBufferSize];
        }

        audioControl.init();
    }

    public synchronized void start() {
        logger.info("starting emulator");

        if (running) return;

        running = true;

        textureBufferFilled = false;
        textureBufferIndex = 0;
        videoBufferIndex = 0;

        emuThread = new Thread(new Runnable() {
            @Override
            public void run() {
                emuLoop();
            }
        });
        emuThread.start();
    }

    private void emuLoop() {
        logger.info("started emulator process");

        if (!Thread.interrupted()) {
            emu = new NativeInterface();

            Log.d("emu", "initializing emulator kernel");

            StringBuilder prefsDocument = new StringBuilder();
            prefsDocument.append("Emul1541Proc = FALSE\n");
            prefsDocument.append("JoystickSwap = FALSE\n");

            if (0 != emu.init(prefsDocument.toString(), 0x0)) {
                Log.e("emu", "failed to initialize emulator kernel");
                return;
            }

            logger.info("initialized emulator kernel");
        }

        clearKeyboardInputQueue();

        long nextUpdateTime = 0;
        long cycleTime = (long) NORMAL_SLEEP_TIME * 1000000;

        boolean vblankOccured = false;

        audioControl.start();
        audioControl.pause();

        while (running && !Thread.interrupted()) {

            // Check for pending disk load (from main thread)
            if (pendingDiskData != null) {
                byte[] diskData = pendingDiskData;
                String diskFilename = pendingDiskFilename;
                pendingDiskData = null;
                pendingDiskFilename = null;

                synchronized (emuLock) {
                    int status = emu.load(Image.TYPE_DISK, diskData, diskData.length, diskFilename);
                    if (status != 0) {
                        logger.warning("failed to attach disk");
                    } else {
                        logger.info("disk attached: " + diskFilename);
                    }
                }
                keyboardInputDelay = 50;
            }

            if (paused) {

                audioControl.pause();

                while (running && paused && !Thread.interrupted()) {
                    try {
                        Thread.sleep(PAUSE_SLEEP_TIME);
                    } catch (InterruptedException e) {
                        break;
                    }
                }

                if (Thread.interrupted()) {
                    break;
                }

                audioControl.reset();

            } else {

                if (vblankOccured) {

                    processKeyboardInputQueue();

                    long currentTime = System.nanoTime();

                    if (currentTime < nextUpdateTime) {
                        long waitTime = (nextUpdateTime - currentTime);
                        try {
                            Thread.sleep((long) (waitTime / 1000000));
                        } catch (InterruptedException e) {
                            break;
                        }
                    }

                    if (0 == nextUpdateTime) {
                        nextUpdateTime = currentTime + cycleTime;
                    } else {
                        nextUpdateTime += cycleTime;
                        if (nextUpdateTime < currentTime - cycleTime * 2) {
                            nextUpdateTime = currentTime - cycleTime * 2;
                        }
                    }
                }

                byte[] videoBuffer = (byte[]) videoBuffers[videoBufferIndex];
                byte[] audioBuffer = audioControl.getInputBuffer();

                int flags = FLAG_UNPACK_GRAPHICS | FLAG_USE_GAMMA_CORRECTION;

                int updateStatus = 0;

                synchronized (emuLock) {
                    updateStatus = emu.update(stickMask, videoBuffer, audioBuffer, flags);
                }

                vblankOccured = (1 == updateStatus);

                if (vblankOccured) {

                    if (false == textureBufferFilled) {
                        textureBufferIndex = videoBufferIndex;
                        videoBufferIndex = (videoBufferIndex + 1) % NUM_VIDEO_BUFFERS;
                        textureBufferFilled = true;
                    }

                    audioControl.nextInputBuffer();
                }
            }
        }

        audioControl.cleanup();

        logger.info("shutdown emulator");

        clearKeyboardInputQueue();

        emu.shutdown();
        emu = null;

        running = false;

        logger.info("finished emulator process");
    }

    public synchronized void stop() {
        if (!running) return;

        running = false;
        paused = false;

        if (null != emuThread) {
            emuThread.interrupt();
        }

        audioControl.stop();

        if (null != emuThread) {
            try {
                emuThread.join(3000);
            } catch (InterruptedException e) {
                ;
            }
            emuThread = null;
            logger.info("stopped emulator");
        }
    }

    public synchronized void pause() {
        paused = true;
    }

    public synchronized void resume() {
        paused = false;
    }

    public boolean isPaused() {
        return paused;
    }

    public void keyInputDelay(int delayCycles) {
        keyboardInputDelay += delayCycles;
    }

    public void keyInput(int keyCode) {
        pushKeyboardInputQueue(keyCode);
    }

    public void keyInput(int[] keySequence) {
        for (int keyCode : keySequence) {
            pushKeyboardInputQueue(keyCode);
        }
    }

    private void pushKeyboardInputQueue(int keyCode) {
        if (keyboardInputQueue.size() < 1024) {
            keyboardInputQueue.add(keyCode);
        }
    }

    private void clearKeyboardInputQueue() {
        keyboardInputQueue.clear();
    }

    private void processKeyboardInputQueue() {
        if (keyboardInputDelay > 0) {
            keyboardInputDelay--;
            return;
        }

        Integer i = keyboardInputQueue.poll();
        if (null != i) {
            emu.input(i);
        }
    }

    private void sendCommand(int command) {
        if (null == emu) {
            return;
        }

        synchronized (emuLock) {
            emu.command(command);
        }
    }

    /**
     * Load a D64 disk image from assets and queue it for the emu thread.
     * Safe to call from any thread.
     */
    public boolean loadDiskFromAssets(Context context, String assetFilename) {
        try {
            AssetManager assetManager = context.getAssets();
            InputStream is = assetManager.open(assetFilename);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            is.close();
            byte[] data = baos.toByteArray();
            logger.info("loaded disk from assets: " + data.length + " bytes");

            // Queue for emu thread to process
            pendingDiskFilename = assetFilename;
            pendingDiskData = data;
            return true;

        } catch (Exception e) {
            logger.error("failed to load disk from assets: " + e.getMessage());
            return false;
        }
    }

    /**
     * Load disk and auto-type LOAD"*",8,1 + RUN after C64 boot delay.
     */
    public void autoStartGame(final Context context) {
        // Load disk data from assets on main thread (just file I/O, no JNI)
        loadDiskFromAssets(context, "We Are Stardust (M).d64");

        // Queue the key sequence - it will wait for keyboardInputDelay (set by disk attach)
        // then type LOAD"*",8,1 + RUN
        keyInput(KeySequence.sequence_Load_Asterisk_8_1_Run);
    }

    public byte[] lockTextureData() {
        if (false == textureBufferFilled) {
            return null;
        }

        byte[] buffer = (byte[]) videoBuffers[textureBufferIndex];
        return buffer;
    }

    public void unlockTextureData() {
        textureBufferFilled = false;
    }

    public void setStick(int stickMask) {
        this.stickMask = stickMask;
    }

    public void setStickFlag(int flag) {
        this.stickMask |= flag;
    }

    public void clearStickFlag(int flag) {
        this.stickMask &= ~flag;
    }

    public int getStick() {
        return this.stickMask;
    }
}
