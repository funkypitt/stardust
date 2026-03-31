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

    // Pending autostart: disk data + key sequence, applied by emu thread after init
    private volatile byte[] pendingDiskData;
    private volatile String pendingDiskFilename;
    private volatile int[] pendingKeySequence;

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
        dbg("Control.start()");

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
        dbg("[emu] emuLoop started");

        try {
            if (!Thread.interrupted()) {
                dbg("[emu] loading native lib...");
                emu = new NativeInterface();
                dbg("[emu] NativeInterface created");

                StringBuilder prefsDocument = new StringBuilder();
                prefsDocument.append("Emul1541Proc = FALSE\n");
                prefsDocument.append("JoystickSwap = FALSE\n");

                dbg("[emu] calling emu.init...");
                if (0 != emu.init(prefsDocument.toString(), 0x0)) {
                    dbg("[emu] ERROR: emu.init failed!");
                    return;
                }

                dbg("[emu] kernel initialized OK");
            }

            clearKeyboardInputQueue();

            long nextUpdateTime = 0;
            long cycleTime = (long) NORMAL_SLEEP_TIME * 1000000;

            boolean vblankOccured = false;

            audioControl.start();
            audioControl.pause();

            dbg("[emu] entering main loop, pendingDisk=" + (pendingDiskData != null));

            // Wait a bit for C64 to boot before processing pending disk
            int bootDelay = 150; // 3 seconds at 50Hz

            while (running && !Thread.interrupted()) {

                // After boot delay, check for pending disk + key sequence
                if (bootDelay > 0) {
                    if (vblankOccured) bootDelay--;

                    if (bootDelay == 0 && pendingDiskData != null) {
                        byte[] diskData = pendingDiskData;
                        String diskFilename = pendingDiskFilename;
                        int[] keySeq = pendingKeySequence;
                        pendingDiskData = null;
                        pendingDiskFilename = null;
                        pendingKeySequence = null;

                        dbg("[emu] attaching disk: " + diskFilename + " (" + diskData.length + " bytes)");
                        synchronized (emuLock) {
                            int status = emu.load(Image.TYPE_DISK, diskData, diskData.length, diskFilename);
                            dbg("[emu] disk load status=" + status);
                        }
                        keyboardInputDelay = 50; // wait 1s after disk attach

                        if (keySeq != null) {
                            dbg("[emu] queuing " + keySeq.length + " key events");
                            for (int keyCode : keySeq) {
                                pushKeyboardInputQueue(keyCode);
                            }
                        }
                    }
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

            dbg("[emu] shutting down...");

            clearKeyboardInputQueue();

            emu.shutdown();
            emu = null;

            running = false;

            dbg("[emu] finished");

        } catch (Throwable t) {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            dbg("[emu] CRASH: " + sw.toString());
        }
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

    /**
     * Prepare auto-start: load D64 data from assets and store the key sequence.
     * Both will be applied by the emu thread after boot delay.
     */
    public void autoStartGame(Context context) {
        try {
            dbg("[autostart] loading D64 from assets...");
            AssetManager assetManager = context.getAssets();
            InputStream is = assetManager.open("We Are Stardust (M).d64");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
            is.close();
            byte[] data = baos.toByteArray();
            dbg("[autostart] D64 loaded: " + data.length + " bytes");

            // Store for emu thread to pick up after boot
            pendingKeySequence = KeySequence.sequence_Load_Asterisk_8_1_Run;
            pendingDiskFilename = "We Are Stardust (M).d64";
            pendingDiskData = data; // set last (volatile flag)
            dbg("[autostart] queued for emu thread");

        } catch (Exception e) {
            dbg("[autostart] ERROR: " + e.getMessage());
        }
    }

    public byte[] lockTextureData() {
        if (false == textureBufferFilled) {
            return null;
        }

        return (byte[]) videoBuffers[textureBufferIndex];
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

    private static void dbg(String msg) {
        ui.StardustActivity.log(msg);
    }
}
