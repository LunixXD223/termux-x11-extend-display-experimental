// Multi-monitor: streams the Virtual-1 output's region of the desktop to a second device over
// a plain TCP socket, encoded as H.264 via the NDK MediaCodec API.
//
// NOTE ON VERIFICATION: this file uses several AMediaCodec/AMediaFormat NDK constants and
// function signatures (createEncoderByType, configure, dequeue/queueInputBuffer,
// dequeueOutputBuffer, the AMEDIAFORMAT_KEY_* and AMEDIACODEC_* constants) that could not be
// verified against a real NDK toolchain in the environment this was written in - there was no
// working Android NDK/CMake build available. Everything here follows the well-established
// public NdkMediaCodec.h / NdkMediaFormat.h API as documented, but if the build fails inside
// this file, start by diffing the exact constant/function names here against
// <media/NdkMediaCodec.h> and <media/NdkMediaFormat.h> in the NDK actually being used to build -
// that's the most likely source of a mismatch, not the surrounding logic.
//
// Design:
//  - One background thread owns a TCP listen socket on LORIE_MONITOR_PORT and accepts exactly
//    one client (the second device, running Termux:X11 in "monitor" mode) at a time. A second
//    connection attempt while one is already active is closed immediately - multi-client
//    fan-out is not supported in this first version.
//  - The renderer thread calls netMonitorOfferFrame() once per redraw (see renderer.c). It is a
//    cheap no-op unless Virtual-1 is enabled, in which case it captures the current frame via
//    rendererCaptureRegion() (Patch 4) and hands it to the encoder thread through a one-slot,
//    latest-frame-wins queue - exactly the "never block rendering on the network" pattern used
//    throughout this feature. If the encoder thread is still busy with the previous frame, the
//    new one silently replaces it.
//  - The encoder thread converts each RGBA frame to I420 on the CPU (simple, not fast - fine
//    for a correctness-first version; revisit in the optimization pass) and feeds it to a
//    buffer-input AMediaCodec H.264 encoder, then forwards whatever comes out to the connected
//    client with simple length-prefixed framing (see wire format below).
//
// Wire format (all multi-byte integers big-endian / network byte order):
//   Every message: [ uint32_t magic = 0x4C4D4F4E ("LMON") ][ uint32_t length ][ uint8_t kind ][ length bytes payload ]
//   kind 0 = raw encoder output bytes, to be fed to the client's decoder in order as received.
//   kind 1 = config: payload is [ uint32_t width ][ uint32_t height ], sent once right after
//            connecting and again any time Virtual-1's mode changes.

#include <media/NdkMediaCodec.h>
#include <media/NdkMediaFormat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <pthread.h>
#include <unistd.h>
#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include "lorie.h"

#define log(...) __android_log_print(ANDROID_LOG_DEBUG, "netmonitor", __VA_ARGS__)
#define loge(...) __android_log_print(ANDROID_LOG_ERROR, "netmonitor", __VA_ARGS__)

#define LORIE_MONITOR_PORT 7893
#define NETMON_MAGIC 0x4C4D4F4Eu
#define NETMON_KIND_VIDEO 0
#define NETMON_KIND_CONFIG 1

// --- Renderer thread -> encoder thread handoff. Single slot, latest-frame-wins: the encoder
// thread is never allowed to make the renderer thread wait for it.
static struct {
    pthread_mutex_t lock;
    pthread_cond_t cond;
    uint8_t *rgba; // w*h*4 bytes, malloc'd; ownership passes to whoever takes hasFrame
    int w, h;
    bool hasFrame;
} captureQueue = { .lock = PTHREAD_MUTEX_INITIALIZER, .cond = PTHREAD_COND_INITIALIZER };

// Called from the renderer thread once per redraw. See rendererCaptureRegion()'s own
// threading constraints - this call must happen from the same thread/EGL context.
void netMonitorOfferFrame(struct lorie_shared_server_state *state) {
    int x, y, w, h;
    uint8_t *buf;

    if (!state || !state->virtualMonitor.enabled)
        return;

    x = state->virtualMonitor.x;
    y = state->virtualMonitor.y;
    w = state->virtualMonitor.width & ~1;  // encoder wants even dimensions
    h = state->virtualMonitor.height & ~1;
    if (w <= 0 || h <= 0)
        return;

    buf = malloc((size_t) w * (size_t) h * 4);
    if (!buf)
        return;

    if (!rendererCaptureRegion(x, y, w, h, buf)) {
        free(buf);
        return;
    }

    pthread_mutex_lock(&captureQueue.lock);
    if (captureQueue.hasFrame)
        free(captureQueue.rgba); // encoder thread didn't keep up with the last frame - drop it
    captureQueue.rgba = buf;
    captureQueue.w = w;
    captureQueue.h = h;
    captureQueue.hasFrame = true;
    pthread_cond_signal(&captureQueue.cond);
    pthread_mutex_unlock(&captureQueue.lock);
}

// Simple BT.601 studio-range RGBA8888 -> I420 (planar YUV 4:2:0) conversion. Not vectorized on
// purpose - correctness first, this is exactly the kind of thing to optimize later once the
// rest of the pipeline is proven to work.
static void rgbaToI420(const uint8_t *rgba, int w, int h, uint8_t *out) {
    uint8_t *yPlane = out;
    uint8_t *uPlane = out + (size_t) w * (size_t) h;
    uint8_t *vPlane = uPlane + (size_t) (w / 2) * (size_t) (h / 2);
    int i, j;

    for (j = 0; j < h; j++) {
        for (i = 0; i < w; i++) {
            const uint8_t *px = rgba + ((size_t) j * w + i) * 4;
            int r = px[0], g = px[1], b = px[2];
            int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
            yPlane[j * w + i] = (uint8_t) (y < 0 ? 0 : y > 255 ? 255 : y);
        }
    }

    for (j = 0; j < h; j += 2) {
        for (i = 0; i < w; i += 2) {
            const uint8_t *px = rgba + ((size_t) j * w + i) * 4;
            int r = px[0], g = px[1], b = px[2];
            int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
            int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
            uPlane[(j / 2) * (w / 2) + i / 2] = (uint8_t) (u < 0 ? 0 : u > 255 ? 255 : u);
            vPlane[(j / 2) * (w / 2) + i / 2] = (uint8_t) (v < 0 ? 0 : v > 255 ? 255 : v);
        }
    }
}

// send() is not guaranteed to write the whole buffer in one call, especially for the larger
// H.264 payloads - looping until everything is sent (or a real error happens) is required, not
// optional, or large frames would spuriously look like a dead connection.
static bool sendAll(int fd, const void *data, size_t len) {
    const uint8_t *p = (const uint8_t *) data;
    while (len > 0) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n <= 0)
            return false;
        p += n;
        len -= (size_t) n;
    }
    return true;
}

// Sends one framed message. Returns false on any socket error (caller should drop the
// connection and go back to accept()).
static bool netMonitorSend(int fd, uint8_t kind, const uint8_t *payload, uint32_t length) {
    uint32_t header[2] = { htonl(NETMON_MAGIC), htonl(length) };

    if (!sendAll(fd, header, sizeof(header)))
        return false;
    if (!sendAll(fd, &kind, sizeof(kind)))
        return false;
    if (length && !sendAll(fd, payload, length))
        return false;
    return true;
}

static bool netMonitorSendConfig(int fd, int w, int h) {
    uint32_t payload[2] = { htonl((uint32_t) w), htonl((uint32_t) h) };
    return netMonitorSend(fd, NETMON_KIND_CONFIG, (const uint8_t *) payload, sizeof(payload));
}

static AMediaCodec *netMonitorCreateEncoder(int w, int h) {
    AMediaCodec *codec = AMediaCodec_createEncoderByType("video/avc");
    AMediaFormat *format;
    media_status_t status;

    if (!codec) {
        loge("Failed to create H.264 encoder");
        return NULL;
    }

    format = AMediaFormat_new();
    AMediaFormat_setString(format, AMEDIAFORMAT_KEY_MIME, "video/avc");
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_WIDTH, w);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_HEIGHT, h);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_COLOR_FORMAT, 19 /* COLOR_FormatYUV420Planar */);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_BIT_RATE, 4000000);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_FRAME_RATE, 30);
    AMediaFormat_setInt32(format, AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);

    status = AMediaCodec_configure(codec, format, NULL, NULL, AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    AMediaFormat_delete(format);
    if (status != AMEDIA_OK) {
        loge("Failed to configure H.264 encoder: %d", status);
        AMediaCodec_delete(codec);
        return NULL;
    }

    if (AMediaCodec_start(codec) != AMEDIA_OK) {
        loge("Failed to start H.264 encoder");
        AMediaCodec_delete(codec);
        return NULL;
    }

    return codec;
}

// Feeds one RGBA frame through the encoder and forwards whatever the encoder has ready to the
// client. Returns false if the connection should be dropped (send failure).
static bool netMonitorEncodeAndSend(AMediaCodec *codec, int fd, const uint8_t *rgba, int w, int h) {
    ssize_t inIdx = AMediaCodec_dequeueInputBuffer(codec, 10000 /* us */);
    if (inIdx >= 0) {
        size_t capacity = 0;
        uint8_t *inBuf = AMediaCodec_getInputBuffer(codec, (size_t) inIdx, &capacity);
        size_t needed = (size_t) w * (size_t) h * 3 / 2;

        if (inBuf && capacity >= needed) {
            rgbaToI420(rgba, w, h, inBuf);
            AMediaCodec_queueInputBuffer(codec, (size_t) inIdx, 0, needed, 0, 0);
        } else {
            loge("Encoder input buffer too small (%zu < %zu), dropping frame", capacity, needed);
            AMediaCodec_queueInputBuffer(codec, (size_t) inIdx, 0, 0, 0, 0);
        }
    }
    // If no input buffer was available this round, we simply skip feeding this frame - the
    // next captured frame will be tried on the next call. Since captureQueue is
    // latest-frame-wins this only ever costs us a stale/dropped frame, never a stall.

    for (;;) {
        AMediaCodecBufferInfo info;
        ssize_t outIdx = AMediaCodec_dequeueOutputBuffer(codec, &info, 0);
        if (outIdx == AMEDIACODEC_INFO_TRY_AGAIN_LATER)
            break;
        if (outIdx == AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED || outIdx == AMEDIACODEC_INFO_OUTPUT_BUFFERS_CHANGED)
            continue;
        if (outIdx < 0)
            break;

        if (info.size > 0) {
            size_t outCap = 0;
            uint8_t *outBuf = AMediaCodec_getOutputBuffer(codec, (size_t) outIdx, &outCap);
            bool ok = outBuf && netMonitorSend(fd, NETMON_KIND_VIDEO, outBuf + info.offset, (uint32_t) info.size);
            AMediaCodec_releaseOutputBuffer(codec, (size_t) outIdx, false);
            if (!ok)
                return false;
        } else {
            AMediaCodec_releaseOutputBuffer(codec, (size_t) outIdx, false);
        }
    }

    return true;
}

// One client connection's lifetime: negotiate size, run the encoder loop until the frame
// queue signals a resize or the socket dies.
static void netMonitorServeClient(int fd) {
    AMediaCodec *codec = NULL;
    int curW = 0, curH = 0;

    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &(int){1}, sizeof(int));

    for (;;) {
        uint8_t *frame;
        int w, h;

        pthread_mutex_lock(&captureQueue.lock);
        while (!captureQueue.hasFrame)
            pthread_cond_wait(&captureQueue.cond, &captureQueue.lock);
        frame = captureQueue.rgba;
        w = captureQueue.w;
        h = captureQueue.h;
        captureQueue.hasFrame = false;
        pthread_mutex_unlock(&captureQueue.lock);

        if (w != curW || h != curH) {
            if (codec) {
                AMediaCodec_stop(codec);
                AMediaCodec_delete(codec);
            }
            codec = netMonitorCreateEncoder(w, h);
            curW = w;
            curH = h;
            if (!codec || !netMonitorSendConfig(fd, w, h)) {
                free(frame);
                break;
            }
        }

        if (!netMonitorEncodeAndSend(codec, fd, frame, w, h)) {
            free(frame);
            break;
        }
        free(frame);
    }

    if (codec) {
        AMediaCodec_stop(codec);
        AMediaCodec_delete(codec);
    }
    close(fd);

    // Drop any frame left queued for us - a fresh connection should start from a clean slate,
    // not replay whatever the previous client never picked up.
    pthread_mutex_lock(&captureQueue.lock);
    if (captureQueue.hasFrame) {
        free(captureQueue.rgba);
        captureQueue.hasFrame = false;
    }
    pthread_mutex_unlock(&captureQueue.lock);
}

static void *netMonitorAcceptThread(__unused void *arg) {
    int listenFd;
    struct sockaddr_in addr = { .sin_family = AF_INET, .sin_addr = { .s_addr = INADDR_ANY }, .sin_port = htons(LORIE_MONITOR_PORT) };

    listenFd = socket(AF_INET, SOCK_STREAM, 0);
    if (listenFd < 0) {
        loge("netmonitor: socket() failed: %s", strerror(errno));
        return NULL;
    }

    setsockopt(listenFd, SOL_SOCKET, SO_REUSEADDR, &(int){1}, sizeof(int));

    if (bind(listenFd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        loge("netmonitor: bind() on port %d failed: %s", LORIE_MONITOR_PORT, strerror(errno));
        close(listenFd);
        return NULL;
    }

    if (listen(listenFd, 1) < 0) {
        loge("netmonitor: listen() failed: %s", strerror(errno));
        close(listenFd);
        return NULL;
    }

    log("netmonitor: listening on port %d", LORIE_MONITOR_PORT);

    for (;;) {
        struct sockaddr_in peer;
        socklen_t peerLen = sizeof(peer);
        int clientFd = accept(listenFd, (struct sockaddr *) &peer, &peerLen);
        if (clientFd < 0) {
            loge("netmonitor: accept() failed: %s", strerror(errno));
            continue;
        }

        log("netmonitor: client connected from %s", inet_ntoa(peer.sin_addr));
        // Only one monitor client at a time in this version - serve it synchronously on this
        // thread. A second connection simply waits in the listen backlog (queue length 1) and
        // will be accepted once this one disconnects.
        netMonitorServeClient(clientFd);
        log("netmonitor: client disconnected");
    }
}

// Starts the (single, long-lived) accept thread. Safe to call more than once - only the first
// call has any effect. Called once from rendererInit().
void netMonitorInit(void) {
    static bool started = false;
    pthread_t t;

    if (started)
        return;
    started = true;

    pthread_create(&t, NULL, netMonitorAcceptThread, NULL);
    pthread_detach(t);
}
