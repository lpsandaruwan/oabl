// oabltrace: LD_PRELOAD tracer for the AI box's Android Auto daemon (sdAutoReverse, 32-bit ARM).
//
// What it sees: every call from the daemon into libSdAutoReverse.so (the 14 entry points it uses),
// into libscreenrecord.so (startRecordScreen) and into libtinyalsa.so (the audio loopback), plus every
// callback libSdAutoReverse makes back into the daemon (the struct passed to
// auto_reverse_interface_register_callbacks is copied and each function pointer that lies in the
// daemon's own code is replaced by a logging slot).
//
// The signatures are unknown, so each wrapper takes 12 raw 32-bit words and forwards all 12 to the
// real function. Registers r0-r3 plus the next 8 stack words reach the callee exactly as they came, so
// any signature of up to 12 words (softfp ABI) is passed through unchanged; the callee ignores what it
// does not take. A 64-bit result comes back in r0:r1, which is what the wrapper returns. Private use on
// the owner's own box; nothing here is meant for another device.
//
// Output: /data/local/tmp/oabl-trace.log (OABL_TRACE_LOG overrides), one line per event:
//   <seconds since start> <pid>/<tid> <event> ...
// Hot functions (video/audio frames, pcm read/write) log the first calls in detail, then a one-second
// summary (count, bytes, mean and max duration) and every call slower than SLOW_US.
// H.264 frames are scanned for NAL types so the keyframe cadence shows up as "IDR gap".
//
// Off switches: OABL_TRACE_NOCB=1 leaves the callback struct untouched.

#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <sys/prctl.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <sys/uio.h>
#include <time.h>
#include <unistd.h>

#define U12 uint32_t a0, uint32_t a1, uint32_t a2, uint32_t a3, uint32_t a4, uint32_t a5, \
            uint32_t a6, uint32_t a7, uint32_t a8, uint32_t a9, uint32_t a10, uint32_t a11
#define A12 a0, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11
typedef uint64_t (*fn12)(U12);

#define SLOW_US 8000
#define DETAIL_FIRST 24
#define MAX_SLOTS 48

static int g_fd = -1;
static char g_path[160];
static unsigned g_enc_logged;
#define LOG_CAP (6 * 1024 * 1024)
static uint64_t g_t0;
static uint32_t g_exe_lo, g_exe_hi, g_exe_base;
static int g_nocb;

static uint64_t now_us(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000ull + ts.tv_nsec / 1000;
}

static void emit(const char *fmt, ...) {
    char buf[1024];
    uint64_t t = now_us() - g_t0;
    int n = snprintf(buf, sizeof buf, "%4u.%06u %d/%d ", (unsigned)(t / 1000000), (unsigned)(t % 1000000),
                     (int)getpid(), (int)syscall(__NR_gettid));
    va_list ap;
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof buf - n - 2, fmt, ap);
    va_end(ap);
    if (n > (int)sizeof buf - 2) n = sizeof buf - 2;
    buf[n++] = '\n';
    if (g_fd >= 0) (void)!write(g_fd, buf, n);
    // Boot-time use: keep the log bounded. One previous file (.1) is kept.
    static unsigned lines;
    if ((++lines & 255) == 0 && g_fd >= 0) {
        struct stat st;
        if (fstat(g_fd, &st) == 0 && st.st_size > LOG_CAP) {
            char old[200];
            snprintf(old, sizeof old, "%s.1", g_path);
            rename(g_path, old);
            int nfd = open(g_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
            if (nfd >= 0) {
                close(g_fd);
                g_fd = nfd;
            }
        }
    }
}

static int peek(uint32_t addr, void *out, size_t n) {
    if (addr < 0x10000) return 0;
    struct iovec l = {out, n}, r = {(void *)(uintptr_t)addr, n};
    return process_vm_readv(getpid(), &l, 1, &r, 1, 0) == (ssize_t)n;
}

// One argument as text: hex, plus a string or the first bytes when it points at readable memory.
static int render_arg(char *o, size_t cap, uint32_t v, int bytes) {
    uint8_t b[32];
    int n = snprintf(o, cap, "0x%x", v);
    size_t got = 0;
    for (size_t want = 32; want >= 4 && !got; want >>= 1)
        if (peek(v, b, want)) got = want;
    if (!got) return n;
    int printable = 0;
    while ((size_t)printable < got && b[printable] >= 0x20 && b[printable] < 0x7f) printable++;
    if (printable >= 3) {
        n += snprintf(o + n, cap - n, "\"%.*s\"", printable, (char *)b);
    } else if (bytes) {
        n += snprintf(o + n, cap - n, "[");
        for (size_t i = 0; i < got && i < 16; i++) n += snprintf(o + n, cap - n, "%02x", b[i]);
        n += snprintf(o + n, cap - n, "]");
    }
    return n;
}

static void render_args(char *o, size_t cap, uint32_t *a, int count, int bytes) {
    size_t n = 0;
    for (int i = 0; i < count && n + 8 < cap; i++) {
        n += snprintf(o + n, cap - n, "%sa%d=", i ? " " : "", i);
        n += render_arg(o + n, cap - n, a[i], bytes);
    }
}

// H.264 NAL types in the first 256 bytes of a buffer that begins with an Annex-B start code, e.g.
// "7,8,5". Returns 0 when the buffer is not video data, 1 for a frame without an IDR, 2 with one.
static int scan_nals(uint32_t addr, char *o, size_t cap) {
    uint8_t b[256];
    size_t got = 0;
    for (size_t want = 256; want >= 8 && !got; want >>= 1)
        if (peek(addr, b, want)) got = want;
    if (got < 8) return 0;
    if (!(b[0] == 0 && b[1] == 0 && (b[2] == 1 || (b[2] == 0 && b[3] == 1)))) return 0;
    int n = 0, found = 0, idr = 0;
    for (size_t i = 0; i + 4 < got; i++) {
        if (b[i] == 0 && b[i + 1] == 0 && (b[i + 2] == 1 || (b[i + 2] == 0 && b[i + 3] == 1))) {
            size_t h = b[i + 2] == 1 ? i + 3 : i + 4;
            if (h >= got) break;
            int t = b[h] & 0x1f;
            n += snprintf(o + n, cap - n, "%s%d", found ? "," : "", t);
            found++;
            if (t == 5) idr = 1;
            i = h;
        }
    }
    return found ? (idr ? 2 : 1) : 0;
}

struct stat_ {
    uint32_t n, slow, total;
    uint64_t sum, max, bytes, win_start, last, maxgap;
};

typedef struct {
    const char *name;
    fn12 real;
    struct stat_ s;
    int hot;       // summary windows after the first calls
    int bytes_arg; // which argument is a byte count for hot functions, or -1
    int video;     // scan arg for H.264 NALs
    int exitlog;   // log the return value always
} Fn;

static uint64_t g_last_idr;
static uint64_t g_idr_count;

static void window(Fn *f, uint64_t now) {
    struct stat_ *s = &f->s;
    if (now - s->win_start < 1000000) return;
    if (s->n && s->win_start)
        emit("sum %s n=%u bytes=%llu mean=%lluus max=%lluus maxgap=%llums slow=%u", f->name, s->n,
             (unsigned long long)s->bytes, (unsigned long long)(s->sum / s->n),
             (unsigned long long)s->max, (unsigned long long)(s->maxgap / 1000), s->slow);
    s->n = s->slow = 0;
    s->sum = s->max = s->bytes = s->maxgap = 0;
    s->win_start = now;
}

static uint64_t call(Fn *f, U12) {
    if (!f->real) f->real = (fn12)dlsym(RTLD_NEXT, f->name);
    if (!f->real) {
        emit("MISSING %s", f->name);
        return 0;
    }
    uint32_t a[12] = {A12};
    __sync_add_and_fetch(&f->s.n, 1);
    uint32_t seq = __sync_add_and_fetch(&f->s.total, 1); // n restarts every summary window; this does not
    int detail = !f->hot || seq <= DETAIL_FIRST;
    char line[600];
    if (detail) {
        render_args(line, sizeof line, a, 6, f->hot || f->video);
        emit("> %s %s", f->name, line);
    }
    char nals[64] = "";
    if (f->video) {
        int kf = scan_nals(a[1], nals, sizeof nals);
        uint64_t t = now_us();
        if (kf == 2) {
            g_idr_count++;
            emit("IDR #%llu nals=%s size=%u gap=%llums", (unsigned long long)g_idr_count, nals, a[2],
                 g_last_idr ? (unsigned long long)((t - g_last_idr) / 1000) : 0ull);
            g_last_idr = t;
        } else if (kf && a[2] > 60000) {
            emit("BIGFRAME nals=%s size=%u", nals, a[2]);
        }
    }
    uint64_t t0 = now_us();
    if (f->s.last && t0 - f->s.last > f->s.maxgap) f->s.maxgap = t0 - f->s.last;
    f->s.last = t0;
    uint64_t r = f->real(A12);
    uint64_t dt = now_us() - t0;
    struct stat_ *s = &f->s;
    s->sum += dt;
    if (dt > s->max) s->max = dt;
    if (f->bytes_arg >= 0) s->bytes += a[f->bytes_arg];
    // pcm_read/pcm_write block for a whole period (about 5 ms) by design: only a long wait is slow.
    uint64_t limit = (f->name[0] == 'p' && f->name[1] == 'c') ? 30000 : SLOW_US;
    if (dt > limit) {
        s->slow++;
        if (f->hot) emit("SLOW %s %lluus ret=%d", f->name, (unsigned long long)dt, (int)(uint32_t)r);
    }
    if (detail || f->exitlog || ((int32_t)(uint32_t)r < 0 && f->hot))
        emit("< %s ret=0x%llx (%d) %lluus", f->name, (unsigned long long)r, (int)(uint32_t)r,
             (unsigned long long)dt);
    if (f->hot) window(f, t0);
    return r;
}

// name, hot, bytes_arg, video, exitlog
#define WRAP(sym, hot, bytes, video, exitlog)                                          \
    static Fn fn_##sym = {#sym, 0, {0, 0, 0, 0, 0, 0}, hot, bytes, video, exitlog};    \
    uint64_t sym(U12) { return call(&fn_##sym, A12); }

WRAP(auto_reverse_server_init, 0, -1, 0, 1)
WRAP(auto_reverse_server_wait, 0, -1, 0, 1)
WRAP(auto_reverse_server_uninit, 0, -1, 0, 1)
WRAP(auto_reverse_video_start, 0, -1, 0, 1)
WRAP(auto_reverse_video_stop, 0, -1, 0, 1)
WRAP(auto_reverse_audio_start, 0, -1, 0, 1)
WRAP(auto_reverse_audio_stop, 0, -1, 0, 1)
WRAP(auto_reverse_back_home, 0, -1, 0, 1)
WRAP(sd_cfg_set_config, 0, -1, 0, 0)
WRAP(sd_cfg_set_config_int, 0, -1, 0, 0)
WRAP(auto_reverse_video_push_frame, 1, 2, 1, 0)
WRAP(auto_reverse_audio_push_frame, 1, 1, 0, 0)
WRAP(auto_reverse_audio_pull_record_frame, 1, 2, 0, 0)
WRAP(pcm_open, 0, -1, 0, 1)
WRAP(pcm_close, 0, -1, 0, 1)
WRAP(pcm_start, 0, -1, 0, 1)
WRAP(pcm_read, 1, 2, 0, 0)
WRAP(pcm_write, 1, 2, 0, 0)

// libscreenrecord.so: the encoder loop runs inside this call for the whole session.
static Fn fn_rec = {"_Z17startRecordScreeniii", 0, {0}, 0, -1, 0, 1};

// ---- Our own encoder in place of libscreenrecord's capture and encode loop ----------------------------
// libscreenrecord hands every encoder buffer to a callback the daemon registered with
// setCallBack(void (*)(void *data, int size)); the daemon's callback does the SPS/IDR bookkeeping and calls
// auto_reverse_video_push_frame. With persist.oabl.enc.own=1, startRecordScreen is replaced: an app_process
// child (tools/box-encoder) mirrors the display, encodes, and sends each buffer over an abstract unix socket
// as [4-byte big-endian length][bytes]; this function reads them and calls the daemon's callback itself.
// If our encoder cannot start or sends nothing, the stock startRecordScreen runs instead.
typedef void (*vcb_t)(void *, int);
static vcb_t g_vcb;
static volatile int g_stop;
static volatile int g_own_sock = -1;
static fn12 real_setcb, real_stoprec;

uint64_t setcb_w(U12) __asm__("_Z11setCallBackPFvPviE");
uint64_t setcb_w(U12) {
    g_vcb = (vcb_t)(uintptr_t)a0;
    emit("setCallBack cb=0x%x (exe+0x%x)", a0, (a0 & ~1u) - g_exe_base);
    if (!real_setcb) real_setcb = (fn12)dlsym(RTLD_NEXT, "_Z11setCallBackPFvPviE");
    return real_setcb ? real_setcb(A12) : 0;
}

uint64_t stoprec_w(U12) __asm__("_Z16stopRecordScreenv");
uint64_t stoprec_w(U12) {
    emit("stopRecordScreen");
    g_stop = 1;
    int fd = g_own_sock;
    if (fd >= 0) shutdown(fd, SHUT_RDWR);
    if (!real_stoprec) real_stoprec = (fn12)dlsym(RTLD_NEXT, "_Z16stopRecordScreenv");
    return real_stoprec ? real_stoprec(A12) : 0;
}

static int own_enabled(void) {
    char v[PROP_VALUE_MAX] = "";
    __system_property_get("persist.oabl.enc.own", v);
    return v[0] == '1';
}

// Waits up to timeout_ms for len bytes; -1 on EOF, error, stop or timeout.
static int read_full(int fd, uint8_t *p, size_t len, int timeout_ms) {
    size_t got = 0;
    uint64_t t0 = now_us();
    while (got < len) {
        if (g_stop) return -1;
        struct pollfd pf = {fd, POLLIN, 0};
        int r = poll(&pf, 1, 500);
        if (r < 0 && errno != EINTR) return -1;
        if (r > 0) {
            ssize_t n = read(fd, p + got, len - got);
            if (n <= 0) return -1;
            got += (size_t)n;
            t0 = now_us();
        } else if ((int)((now_us() - t0) / 1000) > timeout_ms) {
            return -1;
        }
    }
    return 0;
}

extern char **environ;

static int run_own(uint32_t w, uint32_t h, uint32_t fps) {
    vcb_t cb = g_vcb;
    if (!cb) {
        void **p = (void **)dlsym(RTLD_NEXT, "mVideoCallback");
        if (p) cb = (vcb_t)*p;
    }
    if (!cb) {
        emit("own encoder: no video callback known");
        return -1;
    }
    char name[48];
    snprintf(name, sizeof name, "oabl-enc-%d-%u", (int)getpid(), (unsigned)(now_us() & 0xffff));
    int ls = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC, 0);
    struct sockaddr_un sa;
    memset(&sa, 0, sizeof sa);
    sa.sun_family = AF_UNIX;
    memcpy(sa.sun_path + 1, name, strlen(name));
    socklen_t sl = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(name));
    if (ls < 0 || bind(ls, (struct sockaddr *)&sa, sl) < 0 || listen(ls, 1) < 0) {
        emit("own encoder: socket failed (%d)", errno);
        if (ls >= 0) close(ls);
        return -2;
    }

    char dex[PROP_VALUE_MAX] = "", extra[PROP_VALUE_MAX] = "";
    __system_property_get("persist.oabl.enc.dex", dex);
    if (!dex[0]) snprintf(dex, sizeof dex, "/system/framework/oabl-enc.dex");
    __system_property_get("persist.oabl.enc.args", extra);
    if (!extra[0]) snprintf(extra, sizeof extra, "mbps=12 rt=1 repeat=1000");

    char a_sock[80], a_w[24], a_h[24], a_fps[24];
    snprintf(a_sock, sizeof a_sock, "sock=%s", name);
    snprintf(a_w, sizeof a_w, "w=%u", w);
    snprintf(a_h, sizeof a_h, "h=%u", h);
    snprintf(a_fps, sizeof a_fps, "fps=%u", fps);
    char *argv[40];
    int ac = 0;
    argv[ac++] = (char *)"app_process";
    argv[ac++] = (char *)"/system/bin";
    argv[ac++] = (char *)"com.softiva.oablenc.Main";
    argv[ac++] = a_sock;
    argv[ac++] = a_w;
    argv[ac++] = a_h;
    argv[ac++] = a_fps;
    char *save = NULL;
    for (char *t = strtok_r(extra, " ", &save); t && ac < 38; t = strtok_r(NULL, " ", &save)) argv[ac++] = t;
    argv[ac] = NULL;
    char cp[PROP_VALUE_MAX + 16];
    snprintf(cp, sizeof cp, "CLASSPATH=%s", dex);
    char *envp[128];
    int ec = 0;
    for (char **e = environ; e && *e && ec < 120; e++)
        if (strncmp(*e, "CLASSPATH=", 10) && strncmp(*e, "LD_PRELOAD=", 11)) envp[ec++] = *e;
    envp[ec++] = cp;
    envp[ec] = NULL;

    g_stop = 0;
    pid_t pid = fork();
    if (pid == 0) {
        prctl(PR_SET_PDEATHSIG, SIGKILL);
        execve("/system/bin/app_process", argv, envp);
        _exit(127);
    }
    emit("own encoder: pid %d %ux%u@%u dex=%s", (int)pid, w, h, fps, dex);

    int rc = -3;
    uint32_t frames = 0;
    unsigned long long bytes = 0;
    int cs = -1;
    struct pollfd pf = {ls, POLLIN, 0};
    uint64_t waited = now_us();
    while (!g_stop && poll(&pf, 1, 500) <= 0 && (now_us() - waited) < 12000000ull) {
    }
    if (!g_stop && (pf.revents & POLLIN)) cs = accept(ls, NULL, NULL);
    if (cs < 0) {
        emit("own encoder: did not connect in time");
    } else {
        g_own_sock = cs;
        enum { CAP = 6 << 20 };
        uint8_t *buf = (uint8_t *)malloc(CAP);
        uint64_t winstart = now_us(), last = 0, maxgap = 0;
        unsigned winframes = 0;
        unsigned long long winbytes = 0;
        while (buf && !g_stop) {
            uint8_t hdr[4];
            if (read_full(cs, hdr, 4, 20000) < 0) break;
            uint32_t len = ((uint32_t)hdr[0] << 24) | ((uint32_t)hdr[1] << 16) | ((uint32_t)hdr[2] << 8) | hdr[3];
            if (len == 0 || len > CAP) {
                emit("own encoder: bad frame length %u", len);
                break;
            }
            if (read_full(cs, buf, len, 5000) < 0) break;
            uint64_t t = now_us();
            if (last && t - last > maxgap) maxgap = t - last;
            last = t;
            cb(buf, (int)len);
            frames++;
            winframes++;
            bytes += len;
            winbytes += len;
            if (t - winstart >= 5000000ull) {
                emit("own sum 5s frames=%u kbytes=%llu maxgap=%llums", winframes, winbytes / 1000,
                     (unsigned long long)(maxgap / 1000));
                winstart = t;
                winframes = 0;
                winbytes = 0;
                maxgap = 0;
            }
        }
        free(buf);
        g_own_sock = -1;
        close(cs);
    }
    close(ls);
    kill(pid, SIGKILL);
    waitpid(pid, NULL, 0);
    rc = frames > 0 ? 0 : -3;
    emit("own encoder ended: frames=%u bytes=%llu stop=%d rc=%d", frames, bytes, (int)g_stop, rc);
    return rc;
}

uint64_t startRecordScreen_w(U12) __asm__("_Z17startRecordScreeniii");
uint64_t startRecordScreen_w(U12) {
    g_enc_logged = 0; // log this session's encoder configuration in full
    if (own_enabled()) {
        if (run_own(a0, a1, a2) == 0) return 0;
        emit("own encoder failed, running the stock encoder");
    }
    return call(&fn_rec, A12);
}

// ---- Encoder settings: libscreenrecord builds its MediaCodec format with android::AMessage setters.
// Each one is logged ("ENC int bitrate=...") and an integer can be replaced from the environment, e.g.
//   OABL_ENC=bitrate=8000000,i-frame-interval=2   (integers only)
// With nothing set the values pass through unchanged.
static int peek_cstr(uint32_t addr, char *out, size_t cap) {
    out[0] = 0;
    for (size_t want = cap - 1; want >= 1; want >>= 1) {
        if (peek(addr, out, want)) {
            out[want] = 0;
            for (size_t i = 0; i < want; i++)
                if (!out[i]) return 1;
            out[want] = 0;
            return 1;
        }
    }
    return 0;
}

static int enc_override(const char *key, int32_t *value) {
    const char *e = getenv("OABL_ENC");
    size_t kl = strlen(key);
    while (e && *e) {
        if (!strncmp(e, key, kl) && e[kl] == '=') {
            *value = (int32_t)strtol(e + kl + 1, NULL, 10);
            return 1;
        }
        e = strchr(e, ',');
        if (e) e++;
    }
    return 0;
}

static fn12 real_amsg_int, real_amsg_str, real_amsg_float;
// MediaCodec's own message traffic goes through the same setters (err, generation, what ...) every
// 30 ms, so only the first ENC_LOG_MAX are logged, plus the keys that matter while running.
#define ENC_LOG_MAX 170
static int enc_should_log(const char *key) {
    if (g_enc_logged < ENC_LOG_MAX) { g_enc_logged++; return 1; }
    return !strcmp(key, "request-sync") || !strcmp(key, "video-bitrate") || !strcmp(key, "bitrate");
}
uint64_t amsg_set_int(U12) __asm__("_ZN7android8AMessage8setInt32EPKci");
uint64_t amsg_set_int(U12) {
    if (!real_amsg_int) real_amsg_int = (fn12)dlsym(RTLD_NEXT, "_ZN7android8AMessage8setInt32EPKci");
    char key[64];
    if (peek_cstr(a1, key, sizeof key)) {
        int32_t ov;
        if (enc_override(key, &ov)) {
            emit("ENC int %s=%d -> %d (override)", key, (int)a2, (int)ov);
            a2 = (uint32_t)ov;
        } else if (enc_should_log(key)) {
            emit("ENC int %s=%d", key, (int)a2);
        }
    }
    return real_amsg_int(A12);
}
uint64_t amsg_set_str(U12) __asm__("_ZN7android8AMessage9setStringEPKcS2_i");
uint64_t amsg_set_str(U12) {
    if (!real_amsg_str) real_amsg_str = (fn12)dlsym(RTLD_NEXT, "_ZN7android8AMessage9setStringEPKcS2_i");
    char key[64], val[96];
    if (peek_cstr(a1, key, sizeof key) && peek_cstr(a2, val, sizeof val) && enc_should_log(key))
        emit("ENC str %s=\"%s\"", key, val);
    return real_amsg_str(A12);
}
uint64_t amsg_set_float(U12) __asm__("_ZN7android8AMessage8setFloatEPKcf");
uint64_t amsg_set_float(U12) {
    if (!real_amsg_float) real_amsg_float = (fn12)dlsym(RTLD_NEXT, "_ZN7android8AMessage8setFloatEPKcf");
    char key[64];
    float v;
    memcpy(&v, &a2, sizeof v);
    if (peek_cstr(a1, key, sizeof key) && enc_should_log(key)) emit("ENC float %s=%g", key, (double)v);
    return real_amsg_float(A12);
}
static Fn fn_configure = {"_ZN7android10MediaCodec9configureERKNS_2spINS_8AMessageEEERKNS1_INS_7SurfaceEEERKNS1_INS_7ICryptoEEEj", 0, {0}, 0, -1, 0, 1};
uint64_t mc_configure(U12) __asm__("_ZN7android10MediaCodec9configureERKNS_2spINS_8AMessageEEERKNS1_INS_7SurfaceEEERKNS1_INS_7ICryptoEEEj");
uint64_t mc_configure(U12) { return call(&fn_configure, A12); }
static Fn fn_setparams = {"_ZN7android10MediaCodec13setParametersERKNS_2spINS_8AMessageEEE", 0, {0}, 0, -1, 0, 1};
uint64_t mc_setparams(U12) __asm__("_ZN7android10MediaCodec13setParametersERKNS_2spINS_8AMessageEEE");
uint64_t mc_setparams(U12) { return call(&fn_setparams, A12); }

// Callbacks libSdAutoReverse makes into the daemon.
static Fn g_slot_fn[MAX_SLOTS];
static char g_slot_name[MAX_SLOTS][16];
#define SLOT(i)                                                          \
    static uint64_t slot##i(U12) { return call(&g_slot_fn[i], A12); }
SLOT(0) SLOT(1) SLOT(2) SLOT(3) SLOT(4) SLOT(5) SLOT(6) SLOT(7) SLOT(8) SLOT(9) SLOT(10) SLOT(11)
SLOT(12) SLOT(13) SLOT(14) SLOT(15) SLOT(16) SLOT(17) SLOT(18) SLOT(19) SLOT(20) SLOT(21) SLOT(22)
SLOT(23) SLOT(24) SLOT(25) SLOT(26) SLOT(27) SLOT(28) SLOT(29) SLOT(30) SLOT(31) SLOT(32) SLOT(33)
SLOT(34) SLOT(35) SLOT(36) SLOT(37) SLOT(38) SLOT(39) SLOT(40) SLOT(41) SLOT(42) SLOT(43) SLOT(44)
SLOT(45) SLOT(46) SLOT(47)
static fn12 g_slot_tramp[MAX_SLOTS] = {
    slot0, slot1, slot2, slot3, slot4, slot5, slot6, slot7, slot8, slot9, slot10, slot11,
    slot12, slot13, slot14, slot15, slot16, slot17, slot18, slot19, slot20, slot21, slot22, slot23,
    slot24, slot25, slot26, slot27, slot28, slot29, slot30, slot31, slot32, slot33, slot34, slot35,
    slot36, slot37, slot38, slot39, slot40, slot41, slot42, slot43, slot44, slot45, slot46, slot47};

static uint32_t g_cb_copy[64];

// Replaces the registration to wrap each function pointer found in the struct. "Found" means a word
// that points into the daemon's own executable code; anything else is copied as it is.
static Fn fn_register = {"auto_reverse_interface_register_callbacks", 0, {0}, 0, -1, 0, 1};
uint64_t auto_reverse_interface_register_callbacks(U12) {
    uint32_t words[64];
    int got = 0;
    for (int n = 64; n >= 4 && !got; n >>= 1)
        if (peek(a0, words, n * 4)) got = n;
    emit("callbacks struct at 0x%x, %d words readable, exe code 0x%x-0x%x base 0x%x", a0, got, g_exe_lo,
         g_exe_hi, g_exe_base);
    int wrapped = 0;
    memset(g_cb_copy, 0, sizeof g_cb_copy);
    for (int i = 0; i < got; i++) {
        uint32_t w = words[i];
        g_cb_copy[i] = w;
        int code = w >= g_exe_lo && w < g_exe_hi;
        emit("  cb[%d] = 0x%x%s", i, w, code ? " (daemon code)" : "");
        // Thumb pointers have bit 0 set: wrap and keep it, so the target is called in the same mode.
        if (code && !g_nocb && wrapped < MAX_SLOTS) {
            Fn *f = &g_slot_fn[wrapped];
            snprintf(g_slot_name[wrapped], sizeof g_slot_name[wrapped], "cb%d", i);
            f->name = g_slot_name[wrapped];
            f->real = (fn12)(uintptr_t)w;
            f->exitlog = 0;
            f->bytes_arg = -1;
            f->hot = 1;
            g_cb_copy[i] = (uint32_t)(uintptr_t)g_slot_tramp[wrapped];
            emit("  cb[%d] -> exe+0x%x", i, (w & ~1u) - g_exe_base);
            wrapped++;
        }
    }
    if (!got || g_nocb) {
        uint32_t b[12] = {A12};
        return call(&fn_register, b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], b[10], b[11]);
    }
    return call(&fn_register, (uint32_t)(uintptr_t)g_cb_copy, a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11);
}

__attribute__((constructor)) static void init(void) {
    g_t0 = now_us();
    const char *path = getenv("OABL_TRACE_LOG");
    snprintf(g_path, sizeof g_path, "%s", path ? path : "/data/local/tmp/oabl-trace.log");
    g_fd = open(g_path, O_WRONLY | O_CREAT | O_APPEND, 0644);
    g_nocb = getenv("OABL_TRACE_NOCB") != NULL;
    // The daemon starts 64-bit helpers (sh, insmod, setprop) to build the USB gadget. Handing them a
    // 32-bit LD_PRELOAD makes the linker refuse them and the accessory switch never completes, so the
    // variable is dropped here, after this library is already loaded into the daemon.
    unsetenv("LD_PRELOAD");
    FILE *m = fopen("/proc/self/maps", "r");
    char line[512];
    g_exe_lo = 0;
    while (m && fgets(line, sizeof line, m)) {
        if (!strstr(line, "sdAutoReverse") || strstr(line, "libSdAutoReverse")) continue;
        unsigned lo, hi;
        char perm[8];
        if (sscanf(line, "%x-%x %7s", &lo, &hi, perm) != 3) continue;
        if (!g_exe_base) g_exe_base = lo;
        if (perm[2] == 'x') {
            g_exe_lo = lo;
            g_exe_hi = hi;
        }
    }
    if (m) fclose(m);
    char cmd[200] = "";
    FILE *c = fopen("/proc/self/cmdline", "r");
    if (c) {
        size_t n = fread(cmd, 1, sizeof cmd - 1, c);
        for (size_t i = 0; i + 1 < n; i++)
            if (!cmd[i]) cmd[i] = ' ';
        fclose(c);
    }
    emit("oabltrace loaded in '%s'", cmd);
}
