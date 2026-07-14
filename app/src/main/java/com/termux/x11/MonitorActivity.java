package com.termux.x11;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

/**
 * Multi-monitor: "monitor mode" for a second device. Connects to a host device running
 * Termux:X11 (see lorie/netmonitor.c on the native side) and displays its Virtual-1 output -
 * received as an H.264/TCP stream - full-screen, via MediaCodec's Surface-output decode mode.
 *
 * This is a viewer only: it does not send input back to the host yet (touch/mouse/keyboard
 * routing back to the X server, with the correct coordinate offset, is a separate feature - see
 * the multi-monitor roadmap).
 *
 * Wire protocol - must match lorie/netmonitor.c exactly (see that file for the authoritative
 * description):
 *   Every message: [uint32 magic 0x4C4D4F4E "LMON"][uint32 length][uint8 kind][length bytes]
 *   kind 0 (CONFIG) = payload is [uint32 width][uint32 height].
 *   kind 1 (VIDEO)  = payload is one H.264 access unit's worth of encoder output bytes; feed to
 *                     the decoder in order as received.
 */
public class MonitorActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "MonitorActivity";
    private static final int MONITOR_PORT = 7893;
    private static final int MAGIC = 0x4C4D4F4E;
    private static final int KIND_CONFIG = 0;
    private static final int KIND_VIDEO = 1;

    private SurfaceView surfaceView;
    private View centerPanel;
    private View topBar;
    private EditText hostInput;
    private Button connectButton;
    private Button disconnectButton;
    private TextView statusText;
    private TextView statusTextCenter;
    private TextView portText;

    private volatile Surface decoderSurface;
    private volatile boolean running = false;
    private volatile Thread receiverThread;
    // True only while actually streaming video (after the first CONFIG message, immersive
    // fullscreen active) - distinct from 'running', which is also true during the brief
    // "connected, waiting for stream" phase where the top bar is still shown normally.
    private volatile boolean streaming = false;

    // Guards access to currentSocket, which disconnect() (running on the UI thread) closes to
    // unblock the receiver thread's blocking read - Thread.interrupt() does NOT interrupt a
    // blocked java.net.Socket read, closing the socket from another thread is the standard way
    // to cancel one.
    private final Object socketLock = new Object();
    private Socket currentSocket;

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
    public void onBackPressed() {
        // While actively streaming (immersive, panel hidden), BACK is the only way to get out -
        // there is no visible Disconnect button in that state by design. Otherwise, fall back
        // to the platform's normal back behavior (e.g. finishing the Activity).
        if (streaming) {
            disconnect();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        decoderSurface = holder.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Nothing to do here - the decoder is (re)configured against decoderSurface lazily,
        // whenever a CONFIG message arrives - see runReceiver().
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        decoderSurface = null;
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
     * read), stops the receiver thread, releases the decoder, and puts the UI back into the
     * disconnected state. Safe to call multiple times / when nothing is connected.
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
            // Reap off to the side rather than joining here directly - disconnect() is often
            // called from the UI thread (Disconnect button click), and blocking it on join()
            // would risk a visible hitch/ANR. The socket close above already unblocked whatever
            // read the thread was doing; this join is just cleanup hygiene.
            Thread reaper = t;
            new Thread(() -> {
                try {
                    reaper.join(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "monitor-receiver-reaper").start();
        }

        runOnUiThread(this::showDisconnectedUi);
    }

    private void showDisconnectedUi() {
        streaming = false;
        exitImmersiveMode();
        centerPanel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.GONE);
        statusTextCenter.setText(R.string.monitor_status_disconnected);
    }

    private void showConnectingUi() {
        streaming = false;
        exitImmersiveMode();
        centerPanel.setVisibility(View.VISIBLE);
        topBar.setVisibility(View.GONE);
        statusTextCenter.setText(R.string.monitor_status_connecting);
    }

    private void showConnectedUi() {
        // Socket connected, but no video yet - still shows the top bar/Disconnect button
        // normally. Immersive fullscreen only kicks in once actual streaming starts, in
        // showStreamingUi() below.
        streaming = false;
        centerPanel.setVisibility(View.GONE);
        topBar.setVisibility(View.VISIBLE);
    }

    // Called once the first (or any subsequent, e.g. after a resize) CONFIG message is
    // processed and the decoder is actually up - the point where there's real video to show.
    // Hides every control (panel, IP field, Connect/Disconnect button, status text) and enters
    // true edge-to-edge immersive fullscreen, per the "only show the video" requirement. The
    // only way out from here is BACK (see onBackPressed()) or the connection dropping on its
    // own - there is deliberately no visible button in this state.
    private void showStreamingUi() {
        streaming = true;
        centerPanel.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        enterImmersiveMode();
    }

    // Hides the status/navigation bars and makes the window draw edge-to-edge under them, using
    // WindowInsetsController (API 30+, i.e. Android 11 - matches this feature's actual target
    // range). Falls back to the older View.SYSTEM_UI_FLAG_* immersive-sticky flags on API < 30,
    // since this app's minSdkVersion (26) is below that - deprecated but still functional, and
    // keeps this feature from silently no-op'ing (regressing) on those older devices instead of
    // just not using the newest API for it.
    private void enterImmersiveMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            //noinspection deprecation
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    // Restores the status/navigation bars and normal (non-edge-to-edge) window layout. Safe to
    // call even if immersive mode was never entered.
    private void exitImmersiveMode() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
            }
        } else {
            //noinspection deprecation
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void setStatus(String text) {
        runOnUiThread(() -> {
            statusText.setText(text);
            statusTextCenter.setText(text);
        });
    }

    private void runReceiver(String host) {
        MediaCodec decoder = null;

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

                    if (decoder != null) {
                        try {
                            decoder.stop();
                            decoder.release();
                        } catch (Exception e) {
                            Log.w(TAG, "Error stopping previous decoder before reconfigure", e);
                        }
                        decoder = null;
                    }

                    Surface surface = decoderSurface;
                    if (surface == null) {
                        Log.e(TAG, "No output surface yet, dropping connection");
                        break;
                    }

                    MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
                    decoder = MediaCodec.createDecoderByType("video/avc");
                    decoder.configure(format, surface, null, 0);
                    decoder.start();
                    setStatus(getString(R.string.monitor_status_streaming, width, height));
                    runOnUiThread(this::showStreamingUi);
                } else if (kind == KIND_VIDEO && decoder != null) {
                    feedDecoder(decoder, payload);
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
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing decoder", e);
                }
            }
            synchronized (socketLock) {
                currentSocket = null;
            }
            // Only reset to the disconnected UI if this thread is still the current one AND the
            // connection wasn't already torn down via disconnect() (which already flips running
            // to false and updates the UI itself). Without the identity check, a thread that
            // just got superseded by a rapid reconnect could still be unwinding here after the
            // new connection already set running=true again, and would otherwise incorrectly
            // flip the brand-new connection's UI back to "disconnected".
            if (running && Thread.currentThread() == receiverThread) {
                running = false;
                runOnUiThread(this::showDisconnectedUi);
            }
        }
    }

    private static int readBE32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private void feedDecoder(MediaCodec decoder, byte[] data) {
        int inIdx = decoder.dequeueInputBuffer(10_000);
        if (inIdx >= 0) {
            ByteBuffer buf = decoder.getInputBuffer(inIdx);
            if (buf != null) {
                buf.clear();
                buf.put(data);
                decoder.queueInputBuffer(inIdx, 0, data.length, 0, 0);
            }
        }
        // If no input buffer was free this round, this access unit is simply dropped - the
        // stream is live, not a file, so skipping ahead is preferable to blocking the receiver
        // thread (matches the latest-frame-wins philosophy used throughout this feature).

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outIdx;
        while ((outIdx = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
            decoder.releaseOutputBuffer(outIdx, true); // true = render straight to the Surface
        }
    }
}
