package com.termux.x11;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Multi-monitor: "monitor mode" for a second device. Connects to a host device running
 * Termux:X11 (see lorie/netmonitor.c on the native side) and displays its Virtual-1 output -
 * received as raw RGBA8888 tiles over TCP - full-screen, by maintaining a persistent Bitmap of
 * the whole desktop and blitting incoming dirty tiles into it.
 *
 * This is a viewer only: it does not send input back to the host yet (touch/mouse/keyboard
 * routing back to the X server, with the correct coordinate offset, is a separate feature - see
 * the multi-monitor roadmap).
 *
 * Wire protocol - must match lorie/netmonitor.c exactly (see that file for the authoritative
 * description):
 *   Every message: [uint32 magic 0x4C4D4F4E "LMON"][uint32 length][uint8 kind][length bytes]
 *   kind 0 (CONFIG)     = payload is [uint32 width][uint32 height].
 *   kind 1 (DIRTY_RECT) = payload is [uint16 x][uint16 y][uint16 w][uint16 h]
 *                         [uint32 uncompressed length][zlib-compressed RGBA8888 tile pixels].
 */
public class MonitorActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "MonitorActivity";
    private static final int MONITOR_PORT = 7893;
    private static final int MAGIC = 0x4C4D4F4E;
    private static final int KIND_CONFIG = 0;
    private static final int KIND_DIRTY_RECT = 1;

    private SurfaceView surfaceView;
    private View centerPanel;
    private View topBar;
    private EditText hostInput;
    private Button connectButton;
    private Button disconnectButton;
    private TextView statusText;
    private TextView statusTextCenter;
    private TextView portText;

    private volatile boolean surfaceReady = false;
    private volatile boolean running = false;
    private volatile Thread receiverThread;

    // Guards access to currentSocket, which Disconnect (running on the UI thread) closes to
    // unblock the receiver thread's blocking read - Thread.interrupt() does NOT interrupt a
    // blocked java.net.Socket read, closing the socket from another thread is the standard way
    // to cancel one.
    private final Object socketLock = new Object();
    private Socket currentSocket;

    // Only ever touched from the receiver thread (created/replaced on KIND_CONFIG, read/written
    // in applyDirtyRect/drawFrame - both called from the same thread).
    private Bitmap frameBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.monitor_activity);

        surfaceView = findViewById(R.id.monitor_surface);
        centerPanel = findViewById(R.id.monitor_center_panel);
        topBar = findViewById(R.id.monitor_top_bar);
        hostInput = findViewById(R.id.monitor_host_input);
        connectButton = findViewById(R.id.monitor_connect_button);
        disconnectButton = findViewById(R.id.monitor_disconnect_button);
        statusText = findViewById(R.id.monitor_status_text);
        statusTextCenter = findViewById(R.id.monitor_status_text_center);
        portText = findViewById(R.id.monitor_port_text);

        portText.setText(getString(R.string.monitor_port_label, MONITOR_PORT));

        surfaceView.getHolder().addCallback(this);
        connectButton.setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) {
                setStatus(getString(R.string.monitor_status_enter_host));
                return;
            }
            start(host);
        });
        disconnectButton.setOnClickListener(v -> disconnect());

        showDisconnectedUi();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        disconnect();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceReady = true;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Nothing to do here - frameBitmap is (re)created lazily whenever a CONFIG message
        // arrives, see runReceiver().
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        disconnect();
    }

    private void start(String host) {
        disconnect(); // in case a previous connection is still around

        running = true;
        runOnUiThread(this::showConnectingUi);
        receiverThread = new Thread(() -> runReceiver(host), "monitor-receiver");
        receiverThread.start();
    }

    /**
     * Tears the connection down completely: closes the socket (unblocking the receiver thread's
     * read), stops the receiver thread, releases the frame bitmap, and puts the UI back into
     * the disconnected state. Safe to call multiple times / when nothing is connected.
     */
    private void disconnect() {
        running = false;

        synchronized (socketLock) {
            if (currentSocket != null) {
                try {
                    currentSocket.close();
                } catch (IOException e) {
                    Log.w(TAG, "Error closing monitor socket", e);
                }
                currentSocket = null;
            }
        }

        Thread t = receiverThread;
        receiverThread = null;
        if (t != null) {
            // Reap the thread off to the side instead of joining here directly - disconnect()
            // is often called from the UI thread (Disconnect button click), and blocking it on
            // join() would risk a visible hitch/ANR. The join is just cleanup hygiene at this
            // point (the socket close above already unblocked whatever read it was doing).
            Thread reaper = t;
            new Thread(() -> {
                try {
                    reaper.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "monitor-receiver-reaper").start();
        }

        frameBitmap = null;
        runOnUiThread(this::showDisconnectedUi);
    }

    private void showDisconnectedUi() {
        centerPanel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.GONE);
        statusTextCenter.setText(R.string.monitor_status_disconnected);
    }

    private void showConnectingUi() {
        centerPanel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.GONE);
        statusTextCenter.setText(R.string.monitor_status_connecting);
    }

    private void showConnectedUi() {
        centerPanel.setVisibility(View.GONE);
        topBar.setVisibility(View.VISIBLE);
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            statusText.setText(text);
            statusTextCenter.setText(text);
        });
    }

    private void runReceiver(String host) {
        try (Socket socket = new Socket()) {
            synchronized (socketLock) {
                if (!running) {
                    return; // disconnect() already ran before this thread even got going
                }
                currentSocket = socket;
            }

            socket.connect(new InetSocketAddress(host, MONITOR_PORT), 5000);
            setStatus(getString(R.string.monitor_status_connected));
            runOnUiThread(this::showConnectedUi);

            DataInputStream in = new DataInputStream(socket.getInputStream());
            Inflater inflater = new Inflater();

            while (running) {
                int magic = in.readInt(); // DataInputStream reads big-endian, matching htonl() on the host
                if (magic != MAGIC) {
                    Log.e(TAG, "Bad frame magic, dropping connection");
                    break;
                }
                int length = in.readInt();
                int kind = in.readUnsignedByte();
                byte[] payload = new byte[length];
                if (length > 0)
                    in.readFully(payload);

                if (kind == KIND_CONFIG) {
                    if (length < 8) {
                        Log.e(TAG, "Malformed config message, dropping connection");
                        break;
                    }
                    int width = readBE32(payload, 0);
                    int height = readBE32(payload, 4);

                    if (frameBitmap != null)
                        frameBitmap.recycle();
                    frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                    setStatus(getString(R.string.monitor_status_streaming, width, height));
                } else if (kind == KIND_DIRTY_RECT && frameBitmap != null) {
                    if (applyDirtyRect(payload, inflater))
                        drawFrame();
                }
            }
        } catch (IOException e) {
            // A close() triggered by disconnect() also lands here (as a "Socket closed"
            // IOException) - that's expected and not a real error in that case. The identity
            // check also guards against a stale (already-superseded) thread reporting an error
            // for a connection the user has since replaced with a new one.
            if (running && Thread.currentThread() == receiverThread) {
                Log.e(TAG, "Monitor connection error", e);
                setStatus(getString(R.string.monitor_status_error, e.getMessage()));
            }
        } finally {
            synchronized (socketLock) {
                currentSocket = null;
            }
            // Only reset to the disconnected UI if this thread is still the current one AND
            // the connection wasn't already torn down via disconnect() (which already flips
            // running to false itself). Without the identity check, a thread that just got
            // superseded by a rapid reconnect could still be unwinding here *after* the new
            // connection has already set running=true again, and would otherwise incorrectly
            // flip the brand-new connection back to "disconnected".
            if (running && Thread.currentThread() == receiverThread) {
                running = false;
                runOnUiThread(this::showDisconnectedUi);
            }
        }
    }

    private static int readBE32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readBE16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    // Decompresses one dirty-rect payload and blits it into frameBitmap. Returns false (and
    // skips the redraw) on any malformed/corrupt tile - a single bad tile should not crash the
    // whole connection, just get dropped, same as a dropped video frame would be.
    private boolean applyDirtyRect(byte[] payload, Inflater inflater) {
        if (payload.length < 12)
            return false;

        int x = readBE16(payload, 0);
        int y = readBE16(payload, 2);
        int w = readBE16(payload, 4);
        int h = readBE16(payload, 6);
        int rawLen = readBE32(payload, 8);

        if (w <= 0 || h <= 0 || rawLen != w * h * 4)
            return false;

        byte[] raw = new byte[rawLen];
        inflater.reset();
        inflater.setInput(payload, 12, payload.length - 12);
        try {
            int got = 0;
            while (got < rawLen && !inflater.finished()) {
                int n = inflater.inflate(raw, got, rawLen - got);
                if (n == 0)
                    break; // needsDictionary()/needsInput() case we can't satisfy - bail out
                got += n;
            }
            if (got != rawLen)
                return false;
        } catch (DataFormatException e) {
            Log.w(TAG, "Bad compressed tile data, dropping this tile", e);
            return false;
        }

        int[] pixels = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int off = i * 4;
            int r = raw[off] & 0xFF, g = raw[off + 1] & 0xFF, b = raw[off + 2] & 0xFF, a = raw[off + 3] & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        frameBitmap.setPixels(pixels, 0, w, x, y, w, h);
        return true;
    }

    // Redraws the whole persistent bitmap to the surface. Called once per received tile for
    // now - correct but not maximally efficient (a busy host sending many tiles per frame means
    // redundant redraws). Worth batching (e.g. only redraw when no more tiles are immediately
    // available) in a later optimization pass.
    private void drawFrame() {
        if (!surfaceReady || frameBitmap == null)
            return;

        Canvas canvas = surfaceView.getHolder().lockCanvas();
        if (canvas == null)
            return;
        try {
            canvas.drawBitmap(frameBitmap, 0, 0, null);
        } finally {
            surfaceView.getHolder().unlockCanvasAndPost(canvas);
        }
    }
}
