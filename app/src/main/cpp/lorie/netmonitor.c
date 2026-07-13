// Multi-monitor: streams the Virtual-1 output's region of the desktop to a second device over
// a plain TCP socket, as raw (lossless) RGBA8888 with dirty-rectangle updates.
//
// This replaces an earlier H.264/MediaCodec-based version. Rationale for the switch (from a
// live bug report): this feature only ever runs over a local network (LAN/Wi-Fi), where
// bandwidth is cheap and latency/simplicity matter more than compression ratio. A video codec
// also added GOP/keyframe timing concerns and a large area of NDK API surface that was hard to
// verify without a working build. Raw + dirty-rect + zlib is simpler to reason about, easier to
// debug (a corrupt frame is just wrong pixels, not a broken bitstream), and keeps latency lower
// since there's no encoder lookahead/buffering.
//
// Compression: zlib (already linked into this target as "z" for other dependencies) instead of
// LZ4/Zstd - it needs no new library on either side, and the client can decompress with the
// standard java.util.zip.Inflater, which reads zlib's wire format directly. Still lossless,
// just a different (slightly less dense, much simpler to integrate) algorithm than originally
// suggested.
//
// Design:
//  - One background thread owns a TCP listen socket on LORIE_MONITOR_PORT and serves one
//    monitor client at a time (multi-client fan-out is out of scope for this first version).
//  - The renderer thread calls netMonitorOfferFrame() once per redraw (see renderer.c). It is a
//    cheap no-op unless Virtual-1 is enabled, in which case it captures the current frame via
//    rendererCaptureRegion() (Patch 4) and hands it to the diff/send thread through a one-slot
//    latest-frame-wins queue - never block rendering on the network.
//  - The diff/send thread keeps the previously-sent frame around, splits the desktop into fixed
//    TILE_SIZE x TILE_SIZE tiles, memcmp's each tile against the previous frame, and sends only
//    the tiles that changed (each individually zlib-compressed). On connect or resize, there is
//    no previous frame, so every tile is "dirty" - i.e. the first frame after (re)connecting is
//    effectively a full-frame refresh, with no separate wire message kind needed for that case.
//
// Wire format (all multi-byte integers big-endian / network byte order):
//   Every message: [uint32 magic 0x4C4D4F4E ("LMON")][uint32 length][uint8 kind][length bytes]
//   kind 0 (CONFIG)     = payload is [uint32 width][uint32 height]. Sent once right after
//                         connecting and again any time Virtual-1's mode changes.
//   kind 1 (DIRTY_RECT) = payload is [uint16 x][uint16 y][uint16 w][uint16 h]
//                         [uint32 uncompressed length][zlib-compressed RGBA8888 pixels, packed
//                         rows of w*4 bytes each, uncompressed length is w*h*4].

#include <zlib.h>
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
#define NETMON_KIND_CONFIG 0
#define NETMON_KIND_DIRTY_RECT 1
#define TILE_SIZE 64

// --- Renderer thread -> diff/send thread handoff. Single slot, latest-frame-wins: the network
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
    w = state->virtualMonitor.width & ~1;
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
        free(captureQueue.rgba); // diff/send thread didn't keep up with the last frame - drop it
    captureQueue.rgba = buf;
    captureQueue.w = w;
    captureQueue.h = h;
    captureQueue.hasFrame = true;
    pthread_cond_signal(&captureQueue.cond);
    pthread_mutex_unlock(&captureQueue.lock);
}

// send() is not guaranteed to write the whole buffer in one call - loop until everything is
// sent (or a real error happens), or large tiles could spuriously look like a dead connection.
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

// Extracts, compresses (zlib, Z_BEST_SPEED - lowest latency over highest ratio, per the
// real-time nature of this stream), and sends one tile of the desktop.
static bool sendTileRect(int fd, const uint8_t *cur, int fullW, int tx, int ty, int tw, int th) {
    size_t rawSize = (size_t) tw * (size_t) th * 4;
    uint8_t *raw = malloc(rawSize);
    uLongf compCap;
    uint8_t *comp;
    uLongf compLen;
    uint8_t *payload;
    int row;
    bool ok;

    if (!raw)
        return false;

    for (row = 0; row < th; row++) {
        const uint8_t *src = cur + ((size_t) (ty + row) * (size_t) fullW + (size_t) tx) * 4;
        memcpy(raw + (size_t) row * (size_t) tw * 4, src, (size_t) tw * 4);
    }

    compCap = compressBound(rawSize);
    comp = malloc(compCap);
    if (!comp) {
        free(raw);
        return false;
    }

    compLen = compCap;
    if (compress2(comp, &compLen, raw, rawSize, Z_BEST_SPEED) != Z_OK) {
        loge("sendTileRect: zlib compress2 failed");
        free(raw);
        free(comp);
        return false;
    }
    free(raw);

    payload = malloc(12 + compLen);
    if (!payload) {
        free(comp);
        return false;
    }

    {
        uint16_t nx = htons((uint16_t) tx), ny = htons((uint16_t) ty);
        uint16_t nw = htons((uint16_t) tw), nh = htons((uint16_t) th);
        uint32_t nraw = htonl((uint32_t) rawSize);
        memcpy(payload + 0, &nx, 2);
        memcpy(payload + 2, &ny, 2);
        memcpy(payload + 4, &nw, 2);
        memcpy(payload + 6, &nh, 2);
        memcpy(payload + 8, &nraw, 4);
        memcpy(payload + 12, comp, compLen);
    }
    free(comp);

    ok = netMonitorSend(fd, NETMON_KIND_DIRTY_RECT, payload, (uint32_t) (12 + compLen));
    free(payload);
    return ok;
}

// Splits (cur, w x h) into TILE_SIZE tiles, sends only the ones that differ from prev (or every
// tile if prev is NULL - first frame after connect/resize). Returns false on send failure.
static bool netMonitorDiffAndSend(int fd, const uint8_t *cur, const uint8_t *prev, int w, int h) {
    int ty, tx;

    for (ty = 0; ty < h; ty += TILE_SIZE) {
        int th = (ty + TILE_SIZE <= h) ? TILE_SIZE : (h - ty);
        for (tx = 0; tx < w; tx += TILE_SIZE) {
            int tw = (tx + TILE_SIZE <= w) ? TILE_SIZE : (w - tx);
            bool dirty = true;

            if (prev) {
                int row;
                dirty = false;
                for (row = 0; row < th && !dirty; row++) {
                    const uint8_t *a = cur + ((size_t) (ty + row) * (size_t) w + (size_t) tx) * 4;
                    const uint8_t *b = prev + ((size_t) (ty + row) * (size_t) w + (size_t) tx) * 4;
                    if (memcmp(a, b, (size_t) tw * 4) != 0)
                        dirty = true;
                }
            }

            if (dirty && !sendTileRect(fd, cur, w, tx, ty, tw, th))
                return false;
        }
    }
    return true;
}

// One client connection's lifetime: send config on connect/resize, then diff+send frames from
// the capture queue until the socket dies.
static void netMonitorServeClient(int fd) {
    uint8_t *prevFrame = NULL;
    int prevW = 0, prevH = 0;

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

        if (w != prevW || h != prevH) {
            free(prevFrame);
            prevFrame = NULL;
            prevW = w;
            prevH = h;
            if (!netMonitorSendConfig(fd, w, h)) {
                free(frame);
                break;
            }
        }

        if (!netMonitorDiffAndSend(fd, frame, prevFrame, w, h)) {
            free(frame);
            break;
        }

        free(prevFrame);
        prevFrame = frame; // ownership transferred - becomes "previous" for the next round
    }

    free(prevFrame);
    close(fd);

    // Drop any frame left queued for us - a fresh connection should start from a clean slate.
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
