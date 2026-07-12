package com.termux.x11;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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
 *   kind 0 (KIND_VIDEO)  = raw H.264 encoder output; feed to the decoder in order as received.
 *   kind 1 (KIND_CONFIG) = payload is [uint32 width][uint32 height].
 */
public class MonitorActivity extends Activity implements SurfaceHolder.Callback {
    private static final String TAG = "MonitorActivity";
    private static final int MONITOR_PORT = 7893;
    private static final int MAGIC = 0x4C4D4F4E;
    private static final int KIND_VIDEO = 0;
    private static final int KIND_CONFIG = 1;

    private EditText hostInput;
    private Button connectButton;
    private TextView statusText;

    private volatile Surface decoderSurface;
    private volatile boolean running = false;
    private Thread receiverThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.monitor_activity);

        SurfaceView surfaceView = findViewById(R.id.monitor_surface);
        hostInput = findViewById(R.id.monitor_host_input);
        connectButton = findViewById(R.id.monitor_connect_button);
        statusText = findViewById(R.id.monitor_status_text);

        surfaceView.getHolder().addCallback(this);
        connectButton.setOnClickListener(v -> {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) {
                statusText.setText(R.string.monitor_status_enter_host);
                return;
            }
            start(host);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stop();
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        decoderSurface = holder.getSurface();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // Nothing to do: the decoder is (re)configured against decoderSurface lazily, whenever
        // a KIND_CONFIG message arrives - see runReceiver().
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        decoderSurface = null;
        stop();
    }

    private void start(String host) {
        stop(); // in case a previous connection/thread is still winding down

        running = true;
        statusText.setText(R.string.monitor_status_connecting);
        receiverThread = new Thread(() -> runReceiver(host), "monitor-receiver");
        receiverThread.start();
    }

    private void stop() {
        running = false;
        if (receiverThread != null) {
            receiverThread.interrupt();
            receiverThread = null;
        }
    }

    private void setStatus(String text) {
        runOnUiThread(() -> statusText.setText(text));
    }

    private void runReceiver(String host) {
        MediaCodec decoder = null;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, MONITOR_PORT), 5000);
            setStatus(getString(R.string.monitor_status_connected));

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
                        decoder.stop();
                        decoder.release();
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
                } else if (kind == KIND_VIDEO && decoder != null) {
                    feedDecoder(decoder, payload);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Monitor connection error", e);
            setStatus(getString(R.string.monitor_status_error, e.getMessage()));
        } finally {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception e) {
                    Log.w(TAG, "Error releasing decoder", e);
                }
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
        // If no input buffer was free this round, this NAL is simply dropped - the stream is
        // live, not a file, so skipping ahead is preferable to blocking the receiver thread.

        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int outIdx;
        while ((outIdx = decoder.dequeueOutputBuffer(info, 0)) >= 0) {
            decoder.releaseOutputBuffer(outIdx, true); // true = render straight to the Surface
        }
    }
}
