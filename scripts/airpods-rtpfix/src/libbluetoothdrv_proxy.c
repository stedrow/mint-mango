#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define LOG_TAG "BTDUMP"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOG_TAG_CTRL "BTCTRL"
#define LOGCTRL(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_CTRL, __VA_ARGS__)
#define LOG_TAG_READ "BTREAD"
#define LOGREAD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_READ, __VA_ARGS__)
#define LOG_TAG_REWRITE "BTREWRITE"
#define LOGREWRITE(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_REWRITE, __VA_ARGS__)
#define LOG_TAG_RTPFIX "BTRTPFIX"
#define LOGRTPFIX(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_RTPFIX, __VA_ARGS__)
#define LOG_TAG_LSTO "BTLSTO"
#define LOGLSTO(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_LSTO, __VA_ARGS__)
#define LOG_TAG_PSM "BTPSM"
#define LOGPSM(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_PSM, __VA_ARGS__)
#define LOG_TAG_SNOOP "BTSNOOP"
#define LOGSNOOP(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_SNOOP, __VA_ARGS__)

/* Known-good AirPods Pro 2/Y1 mode:
 * - Rewrite SET_CONFIGURATION max_bitpool 0x35 -> 0x23.
 * - Normalize A2DP/SBC RTP timestamps.
 * - Keep media prefix logs enabled for BTDUMP proof.
 *
 * The RTP timestamp fix is required for AirPods compatibility; AirPods silently
 * drop the stream when the old MediaTek stack emits non-normalized timestamps.
 */
#ifndef ENABLE_BT_SETCONFIG_REWRITE
#define ENABLE_BT_SETCONFIG_REWRITE 1
#endif
#ifndef ENABLE_RTP_TIMESTAMP_FIX
#define ENABLE_RTP_TIMESTAMP_FIX 1
#endif
#ifndef ENABLE_VERBOSE_BT_MEDIA_LOG
#define ENABLE_VERBOSE_BT_MEDIA_LOG 1
#endif
/* Shorten the ACL link-supervision timeout so a hand-over-antenna / in-pocket RF
 * dropout is detected in ~4s instead of the controller's ~20s (0x7D00) default.
 * We can't stop the drop (near-field body attenuation is physics), but detecting
 * it fast lets the launcher's reconnect watchdog recover without a manual tap. */
#ifndef ENABLE_LINK_SUPERVISION_TIMEOUT
#define ENABLE_LINK_SUPERVISION_TIMEOUT 1
#endif
/* Units are 0.625ms baseband slots. 8000 * 0.625ms = 5000ms = 5s. Kept well
 * above a brief fade (or a settings-screen inquiry scan bumping the link) so a
 * momentary dip doesn't force a full teardown. */
#ifndef LINK_SUPERVISION_TIMEOUT_SLOTS
#define LINK_SUPERVISION_TIMEOUT_SLOTS 8000
#endif
/* Raw H4 snoop: hex-dump every non-media byte in both directions under the
 * BTSNOOP tag, so the HCI traffic can be reassembled and parsed on the host.
 * The device's standard btsnoop_hci.log hook doesn't work on this firmware
 * (see scripts/airpods-aap/Y2_INVESTIGATION.md), and this is the only view of
 * what actually reaches the air. Diagnostic only -- off in shipping builds. */
#ifndef ENABLE_HCI_SNOOP
#define ENABLE_HCI_SNOOP 0
#endif
/* Redirect mtkbt's internal stack traces into logcat (see the hook code below).
 * Patches another module's code in-process, so diagnostic builds only. */
#ifndef ENABLE_MTKBT_TRACE
#define ENABLE_MTKBT_TRACE 0
#endif
/* Y2's Bluetooth stack puts PSM 0x0000 in every raw L2CAP client Connection
 * Request -- an invalid PSM that peers correctly refuse, which is what makes
 * AapService's connect fail (see scripts/airpods-aap/Y2_INVESTIGATION.md). No
 * BluetoothSocket argument reaches that field, and patching the vendor HAL's
 * connect-message builder doesn't reach the wire either, so the PSM is fixed
 * here, in the last place the packet passes through. PSM 0 is invalid and
 * nothing else on this device ever emits it, so this rewrites exactly the
 * broken request: the stack's own profiles (SDP 0x0001, AVCTP 0x0019, AVDTP
 * 0x0017) carry correct PSMs and are untouched. */
#ifndef ENABLE_L2CAP_PSM_FIX
#define ENABLE_L2CAP_PSM_FIX 0
#endif
#ifndef L2CAP_PSM_FIX_TARGET
#define L2CAP_PSM_FIX_TARGET 0x1001  /* Apple AAP */
#endif
#ifndef __NR_gettid
#define __NR_gettid 224
#endif
#ifndef __ARM_NR_cacheflush
#define __ARM_NR_cacheflush 0xf0002
#endif

#if ENABLE_VERBOSE_BT_MEDIA_LOG
#define LARGE_WRITE_DUMP_LIMIT 300
#else
#define LARGE_WRITE_DUMP_LIMIT 0
#endif

#define SMALL_WRITE_DUMP_LIMIT 500
#define READ_DUMP_LIMIT 800
#define RTPFIX_LOG_LIMIT 300

static void *g_real_handle = NULL;
static int (*g_real_mtk_bt_enable)(void) = NULL;
static int (*g_real_mtk_bt_disable)(int fd) = NULL;
static int (*g_real_mtk_bt_write)(int fd, const void *buf, int len) = NULL;
static int (*g_real_mtk_bt_read)(int fd, void *buf, int len) = NULL;
static int (*g_real_mtk_bt_op)(int op, int fd, int arg3, void *out) = NULL;
static volatile int g_large_write_dump_count = 0;
static volatile int g_small_write_dump_count = 0;
static volatile int g_read_dump_count = 0;
static volatile int g_rewrite_count = 0;
static volatile int g_rtpfix_count = 0;
static uint32_t g_rtp_ts_fixed = 0;
static int g_rtp_ts_active = 0;

/* Serializes the rare injected HCI command (from the RX thread) against normal
 * media/control writes (from the TX thread) so they never interleave on the fd. */
static pthread_mutex_t g_tx_lock = PTHREAD_MUTEX_INITIALIZER;
/* Set when we've injected a Write_Link_Supervision_Timeout and are waiting to
 * swallow its Command_Complete so the host stack never sees it. */
static volatile int g_lsto_awaiting_cc = 0;
static volatile int g_lsto_count = 0;

static const unsigned char kSetConfigBitpool35Pattern[] = {
    0x04, 0x01, 0x00, 0x07, 0x06, 0x00, 0x00, 0x21, 0x15, 0x13, 0x35
};

static void resolve_real_library(void) {
    if (g_real_handle != NULL) {
        return;
    }

    g_real_handle = dlopen("/system/lib/libbluetoothdrv_real.so", RTLD_NOW | RTLD_GLOBAL);
    if (g_real_handle == NULL) {
        LOGD("dlopen failed: %s", dlerror());
        return;
    }

    g_real_mtk_bt_enable = (int (*)(void))dlsym(g_real_handle, "mtk_bt_enable");
    g_real_mtk_bt_disable = (int (*)(int fd))dlsym(g_real_handle, "mtk_bt_disable");
    g_real_mtk_bt_write = (int (*)(int fd, const void *buf, int len))dlsym(g_real_handle, "mtk_bt_write");
    g_real_mtk_bt_read = (int (*)(int fd, void *buf, int len))dlsym(g_real_handle, "mtk_bt_read");
    g_real_mtk_bt_op = (int (*)(int op, int fd, int arg3, void *out))dlsym(g_real_handle, "mtk_bt_op");
}

#if ENABLE_MTKBT_TRACE
/* mtkbt's own stack traces never reach logcat: both of its trace helpers end in
 * a sink that is gated off on a "user" build and would otherwise go to MTK's
 * mobile-log daemon. This proxy is loaded *into* the mtkbt process (mtkbt is the
 * only consumer of libbluetoothdrv.so), so it can redirect those helpers into
 * logcat by overwriting their entry points with a branch to our own variadic
 * stand-ins. Diagnostic only -- this is the sole view into the vendor stack's
 * decisions, e.g. which internal site rejects a raw L2CAP connect.
 *
 * Offsets are file offsets into /system/bin/mtkbt (Ghidra address - 0x10000,
 * since it bases this PIE at 0x10000 and its LOAD maps vaddr == file offset):
 *   0x55084  the plain "printf a message" helper (fully formatted strings)
 *   0x729ac  the leveled trace helper (level, id, fmt, ...)
 * Both are 4-byte aligned, which the branch encoding below requires. */
#define MTKBT_TRACE_PLAIN_OFFSET 0x55084
#define MTKBT_TRACE_LEVEL_OFFSET 0x729ac

#define LOG_TAG_MTK "MTKBT"

static void mtk_trace_plain(const char *fmt, ...) {
    char line[256];
    va_list ap;

    if (fmt == NULL) return;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_MTK, "%s", line);
}

static void mtk_trace_level(int level, int id, const char *fmt, ...) {
    char line[256];
    va_list ap;

    if (fmt == NULL) return;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_MTK, "[%d/%#x] %s", level, id, line);
}

/** True only inside /system/bin/mtkbt -- zygote maps this library too (via the
 *  audio HAL) and must never be patched. */
static int running_in_mtkbt(void) {
    char exe[256];
    ssize_t n = readlink("/proc/self/exe", exe, sizeof(exe) - 1);
    if (n <= 0) return 0;
    exe[n] = '\0';
    return strcmp(exe, "/system/bin/mtkbt") == 0;
}

/** Load address of the main executable: its r-xp mapping at file offset 0. */
static unsigned long mtkbt_load_base(void) {
    FILE *f = fopen("/proc/self/maps", "r");
    char line[512];
    unsigned long base = 0;

    if (f == NULL) return 0;
    while (fgets(line, sizeof(line), f) != NULL) {
        unsigned long start, end, off;
        char perms[8];
        char path[256];
        if (sscanf(line, "%lx-%lx %7s %lx %*s %*s %255s",
                   &start, &end, perms, &off, path) != 5) {
            continue;
        }
        if (off == 0 && perms[2] == 'x' && strcmp(path, "/system/bin/mtkbt") == 0) {
            base = start;
            break;
        }
    }
    fclose(f);
    return base;
}

/* Overwrites 8 bytes with an absolute Thumb branch:
 *   f8df f000   ldr.w pc, [pc]     (reads the word that follows)
 *   <target|1>                     Thumb bit set
 * The originals are never called -- they only log, and their sink is dead. */
static void patch_branch(unsigned long at, void *target) {
    unsigned char code[8];
    unsigned long page = at & ~0xFFFUL;
    unsigned long addr = (unsigned long)target | 1UL;

    if (at == 0) return;
    if (mprotect((void *)page, 0x2000, PROT_READ | PROT_WRITE | PROT_EXEC) != 0) {
        LOGD("mtkbt trace: mprotect failed at %#lx", page);
        return;
    }
    code[0] = 0xDF; code[1] = 0xF8; code[2] = 0x00; code[3] = 0xF0;
    memcpy(code + 4, &addr, 4);
    memcpy((void *)at, code, sizeof(code));
    /* Flush via the ARM cacheflush syscall, not __builtin___clear_cache: the
     * builtin emits a call to __clear_cache, which this device's bionic does not
     * export -- the library then fails to load at all, and since the audio HAL
     * links it too, that takes system_server down with it. */
    syscall(__ARM_NR_cacheflush, at, at + sizeof(code), 0);
    LOGD("mtkbt trace: hooked %#lx -> %p", at, target);
}

static void install_mtkbt_trace_hooks(void) {
    unsigned long base;

    if (!running_in_mtkbt()) return;
    base = mtkbt_load_base();
    if (base == 0) {
        LOGD("mtkbt trace: load base not found");
        return;
    }
    patch_branch(base + MTKBT_TRACE_PLAIN_OFFSET, (void *)mtk_trace_plain);
    patch_branch(base + MTKBT_TRACE_LEVEL_OFFSET, (void *)mtk_trace_level);
}
#endif /* ENABLE_MTKBT_TRACE */

__attribute__((constructor))
static void proxy_init(void) {
    resolve_real_library();
#if ENABLE_MTKBT_TRACE
    install_mtkbt_trace_hooks();
#endif
}

static void log_prefix(const void *buf, int len) {
    int i;
    int limit;
    const unsigned char *bytes;
    char line[128];
    char *p;

    if (buf == NULL || len <= 0) {
        LOGD("prefix: <empty>");
        return;
    }

    bytes = (const unsigned char *)buf;
    limit = len < 32 ? len : 32;
    p = line;
    for (i = 0; i < limit && (p - line) < (int)sizeof(line) - 4; i++) {
        int n = snprintf(p, (size_t)(line + sizeof(line) - p), "%02x ", bytes[i]);
        if (n <= 0) {
            break;
        }
        p += n;
    }
    *p = '\0';
    LOGD("prefix len=%d bytes=%s", len, line);
}

static void log_small_write(int write_index, const void *buf, int len, int result) {
    int i;
    int limit;
    const unsigned char *bytes;
    char line[256];
    char *p;
    long tid;

    if (buf == NULL || len <= 0) {
        LOGCTRL("write_index=%d pid=%d tid=%ld result=%d len=%d bytes=<empty>",
                write_index, getpid(), (long)syscall(__NR_gettid), result, len);
        return;
    }

    bytes = (const unsigned char *)buf;
    limit = len < 64 ? len : 64;
    p = line;
    for (i = 0; i < limit && (p - line) < (int)sizeof(line) - 4; i++) {
        int n = snprintf(p, (size_t)(line + sizeof(line) - p), "%02x ", bytes[i]);
        if (n <= 0) {
            break;
        }
        p += n;
    }
    *p = '\0';
    tid = (long)syscall(__NR_gettid);
    LOGCTRL("write_index=%d pid=%d tid=%ld result=%d len=%d bytes=%s",
            write_index, getpid(), tid, result, len, line);
}

static void log_read_data(int read_index, const void *buf, int len) {
    int i;
    int limit;
    const unsigned char *bytes;
    char line[384];
    char *p;
    long tid;

    if (buf == NULL || len <= 0) {
        LOGREAD("read_index=%d pid=%d tid=%ld result=%d len=%d bytes=<empty>",
                read_index, getpid(), (long)syscall(__NR_gettid), len, len);
        return;
    }

    bytes = (const unsigned char *)buf;
    limit = len < 128 ? len : 128;
    p = line;
    for (i = 0; i < limit && (p - line) < (int)sizeof(line) - 4; i++) {
        int n = snprintf(p, (size_t)(line + sizeof(line) - p), "%02x ", bytes[i]);
        if (n <= 0) {
            break;
        }
        p += n;
    }
    *p = '\0';
    tid = (long)syscall(__NR_gettid);
    LOGREAD("read_index=%d pid=%d tid=%ld result=%d len=%d bytes=%s",
            read_index, getpid(), tid, len, len, line);
}

static int rewrite_set_config_bitpool_if_needed(const void *buf, int len, unsigned char *out_buf, int *matched_offset) {
#if ENABLE_BT_SETCONFIG_REWRITE
    int i;
    int pattern_len;

    if (buf == NULL || out_buf == NULL || matched_offset == NULL || len <= 0 || len > 64) {
        return 0;
    }

    pattern_len = (int)sizeof(kSetConfigBitpool35Pattern);
    if (len < pattern_len) {
        return 0;
    }

    for (i = 0; i <= len - pattern_len; i++) {
        if (memcmp((const unsigned char *)buf + i, kSetConfigBitpool35Pattern, (size_t)pattern_len) == 0) {
            memcpy(out_buf, buf, (size_t)len);
            out_buf[i + pattern_len - 1] = 0x23;
            *matched_offset = i;
            return 1;
        }
    }
    return 0;
#else
    (void)buf;
    (void)len;
    (void)out_buf;
    (void)matched_offset;
    return 0;
#endif
}

static void log_set_config_rewrite_decision(int rewrite_index, int len, int matched_offset) {
    LOGREWRITE("rewrite_index=%d len=%d matched_offset=%d decision=rewrite before=04 01 00 07 06 00 00 21 15 13 35 after=04 01 00 07 06 00 00 21 15 13 23",
               rewrite_index, len, matched_offset);
}

static void log_set_config_rewrite_result(int rewrite_index, int len, int matched_offset, int result) {
    LOGREWRITE("rewrite_index=%d len=%d matched_offset=%d result=%d after_real_mtk_bt_write",
               rewrite_index, len, matched_offset, result);
}

static uint16_t read_be16(const unsigned char *bytes) {
    return (uint16_t)(((uint16_t)bytes[0] << 8) | (uint16_t)bytes[1]);
}

static uint32_t read_be32(const unsigned char *bytes) {
    return ((uint32_t)bytes[0] << 24) |
           ((uint32_t)bytes[1] << 16) |
           ((uint32_t)bytes[2] << 8) |
           (uint32_t)bytes[3];
}

static void write_be32(unsigned char *bytes, uint32_t value) {
    bytes[0] = (unsigned char)((value >> 24) & 0xff);
    bytes[1] = (unsigned char)((value >> 16) & 0xff);
    bytes[2] = (unsigned char)((value >> 8) & 0xff);
    bytes[3] = (unsigned char)(value & 0xff);
}

static int is_sbc_media_packet_for_rtpfix(const unsigned char *bytes, int len, int *frame_count, int *bitpool) {
    int frames;
    int sbc_bitpool;

    if (bytes == NULL || frame_count == NULL || bitpool == NULL || len < 25 || len < 100) {
        return 0;
    }
    if (bytes[0] != 0x02 || bytes[9] != 0x80 || bytes[10] != 0x60) {
        return 0;
    }
    if (bytes[22] != 0x9c || bytes[23] != 0xbd) {
        return 0;
    }
    frames = bytes[21] & 0x0f;
    if (frames < 1 || frames > 15) {
        return 0;
    }
    sbc_bitpool = bytes[24];
    if (sbc_bitpool < 2 || sbc_bitpool > 64) {
        return 0;
    }
    *frame_count = frames;
    *bitpool = sbc_bitpool;
    return 1;
}

static int rewrite_rtp_timestamp_if_needed(const void *buf, int len, unsigned char **owned_buf) {
#if ENABLE_RTP_TIMESTAMP_FIX
    const unsigned char *bytes;
    unsigned char *copy;
    int frame_count;
    int bitpool;
    int rtpfix_index;
    uint16_t seq;
    uint32_t old_ts;
    uint32_t new_ts;
    uint32_t increment;

    if (owned_buf == NULL) {
        return 0;
    }
    *owned_buf = NULL;
    bytes = (const unsigned char *)buf;
    if (!is_sbc_media_packet_for_rtpfix(bytes, len, &frame_count, &bitpool)) {
        return 0;
    }

    rtpfix_index = __sync_fetch_and_add(&g_rtpfix_count, 1);
    copy = (unsigned char *)malloc((size_t)len);
    if (copy == NULL) {
        if (rtpfix_index < RTPFIX_LOG_LIMIT) {
            LOGRTPFIX("index=%d len=%d decision=no_rewrite reason=malloc_failed", rtpfix_index, len);
        }
        return 0;
    }
    memcpy(copy, buf, (size_t)len);

    if (!g_rtp_ts_active) {
        g_rtp_ts_fixed = 0;
        g_rtp_ts_active = 1;
    }

    seq = read_be16(copy + 11);
    old_ts = read_be32(copy + 13);
    new_ts = g_rtp_ts_fixed;
    increment = (uint32_t)frame_count * 128U;
    write_be32(copy + 13, new_ts);
    g_rtp_ts_fixed += increment;

    if (rtpfix_index < RTPFIX_LOG_LIMIT) {
        LOGRTPFIX("index=%d len=%d seq=%u frame_count=%d bitpool=0x%02x old_ts=%u new_ts=%u increment=%u media_header=%02x sbc_header=%02x %02x %02x",
                  rtpfix_index, len, seq, frame_count, bitpool, old_ts, new_ts,
                  increment, copy[21], copy[22], copy[23], copy[24]);
    }

    *owned_buf = copy;
    return 1;
#else
    (void)buf;
    (void)len;
    if (owned_buf != NULL) {
        *owned_buf = NULL;
    }
    return 0;
#endif
}

int mtk_bt_enable(void) {
    resolve_real_library();
    if (g_real_mtk_bt_enable != NULL) {
        return g_real_mtk_bt_enable();
    }
    return -1;
}

int mtk_bt_disable(int fd) {
    resolve_real_library();
    if (g_real_mtk_bt_disable != NULL) {
        return g_real_mtk_bt_disable(fd);
    }
    return -1;
}

#if ENABLE_HCI_SNOOP
/* One log line per transport call: direction, byte count, and up to the first
 * 48 bytes in hex. The transport splits an HCI packet across several calls, so
 * a host-side parser reassembles per direction by concatenating in order --
 * hence "len=" is the call's byte count, not a packet length. Media (>=100B)
 * is skipped; it's the SBC stream and would bury the control traffic. */
static void snoop_dump(char dir, const void *buf, int len) {
    const unsigned char *b = (const unsigned char *)buf;
    char line[208];
    int i;
    int limit;
    char *p;

    if (b == NULL || len <= 0) {
        return;
    }
    if (len > 96) {
        /* Media, or anything too big for one log line. Logged as a gap marker
         * rather than dropped silently, so the host parser knows to resync
         * instead of splicing a hole out of the stream. */
        LOGSNOOP("%c skip=%d", dir, len);
        return;
    }
    limit = len;
    p = line;
    for (i = 0; i < limit; i++) {
        p += sprintf(p, "%02x", b[i]);
    }
    *p = '\0';
    LOGSNOOP("%c len=%d %s", dir, len, line);
}
#endif

#if ENABLE_L2CAP_PSM_FIX
/* Rewrites a PSM-0 L2CAP Connection Request in place in `out` (a copy of the
 * outgoing packet) and returns 1 if it matched. Layout of the H4 ACL packet:
 *   [0]      0x02                     H4 type: ACL
 *   [1..2]   handle + PB/BC flags
 *   [3..4]   ACL length
 *   [5..6]   L2CAP length (8: the 4-byte signalling header plus its 4 bytes)
 *   [7..8]   L2CAP CID (0x0001 = signalling)
 *   [9]      signalling code (0x02 = Connection Request)
 *   [10]     identifier
 *   [11..12] signalling length (4: PSM + source CID)
 *   [13..14] PSM      <- the field the stack leaves as 0
 *   [15..16] source CID
 * The PB flag is checked so a continuation fragment (which has no L2CAP header)
 * can never be mistaken for one of these. */
static int rewrite_l2cap_psm_if_needed(unsigned char *out, int len) {
    int pb;

    if (out == NULL || len < 17 || out[0] != 0x02) {
        return 0;
    }
    pb = (out[2] >> 4) & 0x03;
    if (pb != 0x00 && pb != 0x02) {   /* 0x01 = continuation fragment */
        return 0;
    }
    if (out[7] != 0x01 || out[8] != 0x00) {          /* signalling CID */
        return 0;
    }
    if (out[9] != 0x02) {                            /* Connection Request */
        return 0;
    }
    if (out[11] != 0x04 || out[12] != 0x00) {        /* signalling length 4 */
        return 0;
    }
    if (out[13] != 0x00 || out[14] != 0x00) {        /* only ever fix PSM 0 */
        return 0;
    }
    out[13] = (unsigned char)(L2CAP_PSM_FIX_TARGET & 0xFF);
    out[14] = (unsigned char)((L2CAP_PSM_FIX_TARGET >> 8) & 0xFF);
    return 1;
}
#endif

int mtk_bt_write(int fd, const void *buf, int len) {
    int result;
    int dump_index;
    int small_index;
    int rewrite_index;
    int matched_offset;
    const void *write_buf;
    unsigned char *rtp_rewritten;
    unsigned char rewritten[64];
#if ENABLE_L2CAP_PSM_FIX
    unsigned char psm_fixed[64];
#endif

    resolve_real_library();

    if (g_real_mtk_bt_write == NULL) {
        LOGD("real mtk_bt_write missing");
        return -1;
    }

    write_buf = buf;
    rtp_rewritten = NULL;
    rewrite_index = -1;
    matched_offset = -1;
    if (len > 0 && len <= 64 && buf != NULL) {
        if (rewrite_set_config_bitpool_if_needed(buf, len, rewritten, &matched_offset)) {
            rewrite_index = __sync_fetch_and_add(&g_rewrite_count, 1);
            write_buf = rewritten;
            log_set_config_rewrite_decision(rewrite_index, len, matched_offset);
        }
    }
    if (len >= 100 && buf != NULL) {
        if (rewrite_rtp_timestamp_if_needed(write_buf, len, &rtp_rewritten)) {
            write_buf = rtp_rewritten;
        }
    }

#if ENABLE_L2CAP_PSM_FIX
    if (len >= 17 && len <= (int)sizeof(psm_fixed) && buf != NULL) {
        memcpy(psm_fixed, write_buf, (size_t)len);
        if (rewrite_l2cap_psm_if_needed(psm_fixed, len)) {
            write_buf = psm_fixed;
            LOGPSM("rewrote L2CAP Connection Request psm 0x0000 -> 0x%04x",
                   (unsigned)L2CAP_PSM_FIX_TARGET);
        }
    }
#endif
#if ENABLE_HCI_SNOOP
    snoop_dump('T', write_buf, len);
#endif
    pthread_mutex_lock(&g_tx_lock);
    result = g_real_mtk_bt_write(fd, write_buf, len);
    pthread_mutex_unlock(&g_tx_lock);
    if (matched_offset >= 0) {
        log_set_config_rewrite_result(rewrite_index, len, matched_offset, result);
    }
    if (len > 0 && len <= 64 && buf != NULL) {
        small_index = __sync_fetch_and_add(&g_small_write_dump_count, 1);
        if (small_index < SMALL_WRITE_DUMP_LIMIT) {
            log_small_write(small_index, write_buf, len, result);
        }
    }
    if (len >= 100 && buf != NULL) {
        dump_index = __sync_fetch_and_add(&g_large_write_dump_count, 1);
        if (dump_index < LARGE_WRITE_DUMP_LIMIT) {
            log_prefix(write_buf, len);
        }
    }
    if (rtp_rewritten != NULL) {
        free(rtp_rewritten);
    }
    return result;
}

#if ENABLE_LINK_SUPERVISION_TIMEOUT
/* This transport delivers one HCI event across several mtk_bt_read calls (a
 * 1-byte type, a 2-byte header, then the payload) rather than one call per
 * whole packet. reassemble_hci_event() blocks on the real driver until a full
 * event is collected, so the checks below always see a complete packet.
 * ponytail: fixed cap, drop bytes if a payload ever exceeds it (no event we
 * care about does; raise EVT_BUF_CAP if a larger one shows up). */
#define EVT_BUF_CAP 64
static unsigned char g_evt_fifo[EVT_BUF_CAP];
static int g_evt_fifo_len = 0;

static int reassemble_hci_event(int fd, unsigned char *full, int pre_len, int cap) {
    int got = pre_len;
    int need;
    int r;

    while (got < 3) {
        r = g_real_mtk_bt_read(fd, full + got, 3 - got);
        if (r <= 0) {
            return got;
        }
        got += r;
    }
    need = 3 + full[2];
    if (need > cap) {
        need = cap;
    }
    while (got < need) {
        r = g_real_mtk_bt_read(fd, full + got, need - got);
        if (r <= 0) {
            break;
        }
        got += r;
    }
    return got;
}

/* True only for the exact 9-byte Command_Complete the controller returns for the
 * Write_Link_Supervision_Timeout (opcode 0x0C37) we injected:
 *   04 0E 06 <num_cmd> 37 0C <status> <handle_lo> <handle_hi>
 * The len==9 guard means we only ever discard our own single-packet event and
 * never touch a batched read buffer. Nothing else produces a 0x0C37 CC. */
static int is_our_lsto_cmd_complete(const void *buf, int len) {
    const unsigned char *b = (const unsigned char *)buf;
    if (b == NULL || len != 9) {
        return 0;
    }
    if (b[0] != 0x04 || b[1] != 0x0E || b[2] != 0x06) {
        return 0;
    }
    return b[4] == 0x37 && b[5] == 0x0C;
}

/* When an ACL Connection_Complete comes back, inject a Write_Link_Supervision_
 * Timeout for that handle to shrink the controller's ~20s default. The handle is
 * only known after connection, and the timeout resets to default on every new
 * ACL link, so this fires once per connection (Connection_Complete is one-shot). */
static void maybe_set_link_supervision_timeout(int fd, const void *buf, int len) {
    const unsigned char *b = (const unsigned char *)buf;
    unsigned char cmd[8];
    int handle;
    int lsto_index;

    if (b == NULL || len < 14 || g_real_mtk_bt_write == NULL) {
        return;
    }
    /* HCI event 04 03 0B -> Connection_Complete, param_total_len 11 */
    if (b[0] != 0x04 || b[1] != 0x03 || b[2] != 0x0B) {
        return;
    }
    if (b[3] != 0x00) {   /* status: only act on a successful connection */
        return;
    }
    if (b[12] != 0x01) {  /* link_type: 0x01 = ACL (skip SCO/eSCO) */
        return;
    }
    handle = b[4] | ((b[5] & 0x0F) << 8);

    /* HCI_Write_Link_Supervision_Timeout, OGF 0x03 / OCF 0x0037 -> opcode 0x0C37.
     * H4 command: 01 37 0C 04 <handle LE> <timeout slots LE>. */
    cmd[0] = 0x01;
    cmd[1] = 0x37;
    cmd[2] = 0x0C;
    cmd[3] = 0x04;
    cmd[4] = (unsigned char)(handle & 0xFF);
    cmd[5] = (unsigned char)((handle >> 8) & 0x0F);
    cmd[6] = (unsigned char)(LINK_SUPERVISION_TIMEOUT_SLOTS & 0xFF);
    cmd[7] = (unsigned char)((LINK_SUPERVISION_TIMEOUT_SLOTS >> 8) & 0xFF);

    g_lsto_awaiting_cc = 1;
    pthread_mutex_lock(&g_tx_lock);
    g_real_mtk_bt_write(fd, cmd, (int)sizeof(cmd));
    pthread_mutex_unlock(&g_tx_lock);

    lsto_index = __sync_fetch_and_add(&g_lsto_count, 1);
    LOGLSTO("index=%d handle=0x%03x set link_supervision_timeout slots=%u (~%ums)",
            lsto_index, handle, (unsigned)LINK_SUPERVISION_TIMEOUT_SLOTS,
            (unsigned)(LINK_SUPERVISION_TIMEOUT_SLOTS * 625U / 1000U));
}
#endif /* ENABLE_LINK_SUPERVISION_TIMEOUT */

int mtk_bt_read(int fd, void *buf, int len) {
    int result;
    int read_index;
    unsigned char *out = (unsigned char *)buf;

    resolve_real_library();
    if (g_real_mtk_bt_read == NULL) {
        return -1;
    }

#if ENABLE_LINK_SUPERVISION_TIMEOUT
    /* Serve bytes left over from a previously reassembled event before doing
     * any new real read. */
    if (g_evt_fifo_len > 0 && buf != NULL && len > 0) {
        int n = g_evt_fifo_len < len ? g_evt_fifo_len : len;
        memcpy(buf, g_evt_fifo, (size_t)n);
        memmove(g_evt_fifo, g_evt_fifo + n, (size_t)(g_evt_fifo_len - n));
        g_evt_fifo_len -= n;
        result = n;
        goto dump_and_return;
    }
#endif

    for (;;) {
        result = g_real_mtk_bt_read(fd, buf, len);
#if ENABLE_LINK_SUPERVISION_TIMEOUT
        if (result >= 1 && out != NULL && out[0] == 0x04) {
            /* Start of an HCI event: reassemble the whole packet (this
             * transport fragments it across multiple reads) before deciding
             * whether it's Connection_Complete or our own injected command's
             * Command_Complete. */
            unsigned char full[EVT_BUF_CAP];
            int full_len;
            int copy_n = result < (int)sizeof(full) ? result : (int)sizeof(full);

            memcpy(full, buf, (size_t)copy_n);
            full_len = reassemble_hci_event(fd, full, copy_n, (int)sizeof(full));

            if (g_lsto_awaiting_cc && full_len == 9 && is_our_lsto_cmd_complete(full, full_len)) {
                g_lsto_awaiting_cc = 0;
                LOGLSTO("swallowed injected Command_Complete (len=%d)", full_len);
                continue;
            }
            if (full_len >= 14) {
                maybe_set_link_supervision_timeout(fd, full, full_len);
            }

            {
                int n = full_len < len ? full_len : len;
                memcpy(buf, full, (size_t)n);
                if (full_len > n) {
                    g_evt_fifo_len = full_len - n;
                    memcpy(g_evt_fifo, full + n, (size_t)g_evt_fifo_len);
                }
                result = n;
            }
        }
#endif
        break;
    }

#if ENABLE_LINK_SUPERVISION_TIMEOUT
dump_and_return:
#endif
#if ENABLE_HCI_SNOOP
    if (result > 0) {
        snoop_dump('R', buf, result);
    }
#endif
    if (result > 0 && result <= 128 && buf != NULL) {
        read_index = __sync_fetch_and_add(&g_read_dump_count, 1);
        if (read_index < READ_DUMP_LIMIT) {
            log_read_data(read_index, buf, result);
        }
    }

    return result;
}

int mtk_bt_op(int op, int fd, int arg3, void *out) {
    resolve_real_library();
    if (g_real_mtk_bt_op != NULL) {
        return g_real_mtk_bt_op(op, fd, arg3, out);
    }
    return -1;
}
