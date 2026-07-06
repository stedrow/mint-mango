#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
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
/* Units are 0.625ms baseband slots. 6400 * 0.625ms = 4000ms = 4s. Kept well
 * above a brief fade so a momentary signal dip doesn't force a full teardown. */
#ifndef LINK_SUPERVISION_TIMEOUT_SLOTS
#define LINK_SUPERVISION_TIMEOUT_SLOTS 6400
#endif
#ifndef __NR_gettid
#define __NR_gettid 224
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

__attribute__((constructor))
static void proxy_init(void) {
    resolve_real_library();
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

int mtk_bt_write(int fd, const void *buf, int len) {
    int result;
    int dump_index;
    int small_index;
    int rewrite_index;
    int matched_offset;
    const void *write_buf;
    unsigned char *rtp_rewritten;
    unsigned char rewritten[64];

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

    resolve_real_library();
    if (g_real_mtk_bt_read == NULL) {
        return -1;
    }

    for (;;) {
        result = g_real_mtk_bt_read(fd, buf, len);
#if ENABLE_LINK_SUPERVISION_TIMEOUT
        if (g_lsto_awaiting_cc && is_our_lsto_cmd_complete(buf, result)) {
            /* Swallow our injected command's Command_Complete and fetch the next
             * real packet, so the host stack never sees an event for a command
             * it didn't send. */
            g_lsto_awaiting_cc = 0;
            LOGLSTO("swallowed injected Command_Complete (len=%d)", result);
            continue;
        }
#endif
        break;
    }

    if (result > 0 && result <= 128 && buf != NULL) {
        read_index = __sync_fetch_and_add(&g_read_dump_count, 1);
        if (read_index < READ_DUMP_LIMIT) {
            log_read_data(read_index, buf, result);
        }
    }

#if ENABLE_LINK_SUPERVISION_TIMEOUT
    maybe_set_link_supervision_timeout(fd, buf, result);
#endif

    return result;
}

int mtk_bt_op(int op, int fd, int arg3, void *out) {
    resolve_real_library();
    if (g_real_mtk_bt_op != NULL) {
        return g_real_mtk_bt_op(op, fd, arg3, out);
    }
    return -1;
}
