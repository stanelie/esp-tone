/*
 * esp-tone v2 — throughput spike.
 *
 * Purpose: answer ONE question before any product code is written — what audio bitrate can an
 * ESP8266 actually sustain when the firmware is not fighting itself? See ../../SPEC.md §7.3.
 *
 * Deliberately minimal, and deliberately plain where the old firmware was clever:
 *   - stock heap, and NO dynamic allocation at all on the audio path (static buffers only),
 *   - stock SDK I2S driver (the fork's i2s_custom is only a concurrency refactor of it),
 *   - one task does capture AND send, so there are no pools, queues or hand-off buffers to get
 *     a length wrong in. The old firmware's confirmed ~1.5 KB overrun (SPEC.md §5.1) had to live
 *     in that machinery; here there is none.
 *
 * Not in scope: settings, NVS, AGC, discovery, AT console, concealment, reconnect policy beyond
 * "retry forever". Those come after the platform question is answered.
 *
 * Control (UDP port 3950, so a plain `nc`/python receiver can drive it):
 *   "HELLO" -> start streaming audio to whoever sent it
 *   "STOP"  -> stop
 *   "R32" / "R44" -> switch sample rate (the open listening test, SPEC.md §4.1)
 *
 * Audio packet (native): 8-byte header then little-endian int16 mono samples.
 *   [0]=0xE7 [1]=0x02  [2]=capture bits (16|24)  [3]=sent bits (16)  [4..7]=seq (LE32)
 *   [8..11]=actual hardware sample rate (LE32)
 *
 * It ALSO speaks just enough of the old EASSP protocol for the existing Android app to play this
 * stream unchanged: reply to DISCOVER with INFO, accept CONFIGURE, and use EASSP's 16-byte audio
 * header. That is only so the spike can be judged by ear on the real phone (32 kHz vs 44.1 kHz,
 * and whether the dropouts are phone-side); the protocol itself is not meant to survive (SPEC.md
 * 2.3). Whichever client starts the stream decides the header format used.
 */

#include <string.h>
#include <stdlib.h>
#include <errno.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_system.h"
#include "esp_log.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "nvs_flash.h"
#include "tcpip_adapter.h"
#include "driver/i2s.h"
#include "esp8266/i2s_struct.h"   /* bck_div_num / clkm_div_num: see pick_dividers() */
#include "lwip/sockets.h"
#include "wifi_config.h"

static const char *TAG = "spike";

#define CTRL_PORT        3950
/* EASSP codec ids, as the Android app already understands them. */
#define CODEC_ADPCM      5
#define CODEC_PCM        6
#define RATE_32K         32000
#define RATE_44K         44100
/* Frame length, and so the packet rate (200/s at 5 ms) and the capture delay.
 *
 * 5 ms rather than 10: the frame is the first term in the end-to-end latency, and it also sets how
 * coarsely packets bunch when the air is busy. MEASURED on the receiver at 10 ms frames: mean
 * spacing a clean 10.0 ms, but p99 26 ms and 4.8% of packets arriving back-to-back, which the
 * player can only absorb with buffer depth. Halving the frame halves both the capture delay and
 * the granularity of that bunching. It costs packet overhead: 12 bytes of header per 440 rather
 * than per 880, so ~723 kbit/s instead of ~707 at 44.1 kHz. */
#define FRAME_MS         5

/* Buffers are sized from the rates themselves, not from a hand-written number. Adding 48 kHz
 * earlier silently broke this: MAX_SAMPLES was 448 (sized for 44.1 kHz) while a 48 kHz frame needs
 * 480, and a too-small value here does not fail - it quietly clamps every frame short. Deriving it
 * and asserting at compile time means that cannot happen again: add a faster rate and either the
 * size follows or the build stops. */
#define SAMPLES_AT(r)    ((r) * FRAME_MS / 1000)
#define MAX_SAMPLES      (SAMPLES_AT(RATE_44K) + 8)
_Static_assert(MAX_SAMPLES >= SAMPLES_AT(RATE_32K), "MAX_SAMPLES too small for RATE_32K");
_Static_assert(MAX_SAMPLES >= SAMPLES_AT(RATE_44K), "MAX_SAMPLES too small for RATE_44K");
#define HDR_BYTES        12
#define GUARD_MAGIC      0xA5C3F00Du

/* ---- Static audio buffers, bracketed by guards ----
 * The old firmware's fault was a write running past a heap block into its neighbour. There is no
 * heap here, so instead each buffer is bracketed by magic words that are verified every frame:
 * if anything overruns, we detect it immediately and report it rather than corrupting the radio. */
static uint32_t g_guard0 = GUARD_MAGIC;
static int16_t  g_samples[MAX_SAMPLES];
static uint32_t g_guard1 = GUARD_MAGIC;
/* 24-bit capture: the DMA gives one left-justified 24-bit sample per 32-bit word (4 bytes per
 * sample, 8 bits padding), so it needs its own raw buffer before being reduced to int16. */
static uint32_t g_raw[MAX_SAMPLES];
static uint32_t g_guard1b = GUARD_MAGIC;
static uint8_t  g_packet[16 + MAX_SAMPLES * 2];   /* 16 = the larger (EASSP) header */
static uint32_t g_guard2 = GUARD_MAGIC;

/* ---- Counters (read from the stats task; single writer, so no locking) ---- */
static volatile uint32_t s_frames, s_packets, s_send_fail, s_last_errno;
static volatile uint32_t s_tx_bytes;   /* measured, so the rate reflects the codec in use */
static volatile uint32_t s_i2s_timeouts, s_guard_hits, s_short_reads;
/* Sanity check on the DMA structure described at CAPTURE_OVERSAMPLE: the share of samples that
 * repeat at lag 3, which is where the duplicate actually sits. Expect ~100%. Near 0 would mean the
 * peripheral has stopped interleaving a delayed copy and the decimation is discarding real audio. */
static volatile uint32_t s_dup_pairs, s_dup_match;
static volatile uint32_t s_i2s_reinits;
/* Set by the stats task, acted on by the audio task. The driver must not be uninstalled while
 * another task is blocked in i2s_read(), so the reader does its own re-init. */
static volatile bool s_i2s_reinit_req;
static volatile uint32_t s_rate = RATE_32K;
/* Digital gain: a plain multiplier, so 32 == x32 == +30 dB (same meaning as the old firmware's
 * "gain=32 (+30dB)"). The INMP441 is -26 dBFS sensitivity, so raw samples are very quiet and real
 * gain is a requirement, not a nicety (SPEC.md R4). Saturating, and clips are counted so
 * "too much gain" can be told apart from a genuinely loud source. */
/* Defaults to 1 = pass-through. Gain lives in the player: the mic's own noise floor (-61 dBFS) is
 * ~35 dB above 16-bit quantization noise, so there is no detail for gain-before-truncation to
 * save, and ESP-side gain would only bake clipping irreversibly into the stream. Kept here for
 * experiments, and because gain SHOULD move back onto the device if we ever encode to ADPCM
 * (a quiet signal quantizes badly into 4 bits). */
static volatile uint32_t s_gain = 1;

/* ---- IMA ADPCM, DVI4 layout (RFC 3551) ----
 * 4 bits per sample, so roughly a quarter of the bandwidth: MEASURED on real captures from this
 * microphone, 727 -> 208 kbit/s at 44.1 kHz and 538 -> 160 at 32 kHz, for 40.4 and 47.9 dB SNR.
 * That is below the INMP441's own 61 dB, so it is audibly lossy on noise-like material - the point
 * is that it fits through a congested or long-haul link where PCM will not.
 *
 * Every packet carries the predictor and step index it starts from, so a lost packet costs exactly
 * that packet and the next one resynchronises. The encoder keeps running state across packets and
 * simply writes it into each header, which avoids both drift and the discontinuity that resetting
 * to zero every 5 ms would produce.
 *
 * The decoder already exists on the Android side (Eassp.AdpcmDecoder) and this was checked against
 * it line for line before being written here. */
static volatile uint32_t s_codec = CODEC_PCM;
static int s_ad_pred;
static int s_ad_index;

static const int8_t ADPCM_INDEX[16] = { -1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8 };
static const int16_t ADPCM_STEP[89] = {
    7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,88,97,107,118,
    130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,544,598,658,724,796,876,963,
    1060,1166,1282,1411,1552,1707,1878,2066,2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,
    5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,
    27086,29794,32767 };

static uint8_t adpcm_code(int16_t sample, int *pred, int *index)
{
    int step = ADPCM_STEP[*index];
    int d = (int)sample - *pred;
    uint8_t code = 0;
    if (d < 0) { code = 8; d = -d; }
    if (d >= step)        { code |= 4; d -= step; }
    if (d >= (step >> 1)) { code |= 2; d -= step >> 1; }
    if (d >= (step >> 2)) { code |= 1; }

    /* Reconstruct exactly as the decoder will, or the two drift apart. */
    int diff = step >> 3;
    if (code & 4) diff += step;
    if (code & 2) diff += step >> 1;
    if (code & 1) diff += step >> 2;
    int p = (code & 8) ? *pred - diff : *pred + diff;
    if (p > 32767) p = 32767; else if (p < -32768) p = -32768;
    *pred = p;

    int ix = *index + ADPCM_INDEX[code];
    if (ix < 0) ix = 0; else if (ix > 88) ix = 88;
    *index = ix;
    return code;
}

/* Returns the payload length written to [dst]: 4-byte state header then two samples per byte. */
static size_t adpcm_encode(const int16_t *src, size_t n, uint8_t *dst)
{
    int pred = s_ad_pred, index = s_ad_index;
    size_t o = 0;
    dst[o++] = (uint8_t)pred;
    dst[o++] = (uint8_t)((unsigned)pred >> 8);
    dst[o++] = (uint8_t)index;
    dst[o++] = 0;
    n &= ~(size_t)1;                      /* whole bytes only; frames are already even */
    for (size_t i = 0; i < n; i += 2)
    {
        uint8_t hi = adpcm_code(src[i], &pred, &index);
        uint8_t lo = adpcm_code(src[i + 1], &pred, &index);
        dst[o++] = (uint8_t)((hi << 4) | lo);
    }
    s_ad_pred = pred; s_ad_index = index;
    return o;
}
static volatile uint32_t s_clips;
/* I2S word width used for CAPTURE (16 or 24). Transmission is always 16-bit; 24-bit capture exists
 * because the INMP441 wants fSCK = 64 x fWS and the ESP8266 driver can only give 32 (16-bit) or
 * 48 (24-bit) SCK per stereo frame - so 24-bit is the closest this platform can get to spec, and
 * measured level/response differ between rates with 32. */
static volatile uint32_t s_capture_bits = 16;
/* The real rate the hardware lands on, sent in every packet so no receiver ever has to infer it
 * (that inference is what made 44100 play as 43860 a trap).
 *
 * CAPTURE_OVERSAMPLE: the peripheral hands DMA two 16-bit words per sample, the second being the
 * PREVIOUS sample - measured as y[2k] = s[k], y[2k+1] = s[k-1], i.e. 100% bit-exact repeats at
 * lag 3 in the undecimated stream (`... -35 -38 -39 -35 -46 -39 -56 -46 ...`). That makes the
 * stream S(z^2)(1 + z^-3), which must notch at sr/6, and the undecimated capture measures -19.5 dB
 * at 10 kHz against sr/6 = 10684 Hz. So the word rate is twice the sample rate and half of it is a
 * delayed copy.
 *
 * Read at the plain requested rate this went unnoticed: the capture came back with every sample
 * twice, the reported rate was 2x the truth, the two errors cancelled so the pitch was right, and
 * only the bandwidth was missing. Asking for 2x and taking every other word recovers the true
 * sequence exactly - no filtering needed, because the discarded words are copies, not distinct
 * samples. MEASURED: the near-Nyquist image drops from -20.2 dBc to -45.9 dBc and the audible
 * warble goes away.
 *
 * Which phase we take does not matter: both carry the whole signal, one sample of delay apart.
 * What does matter is that the phase stays PUT, which is why the frame length is forced even - an
 * odd frame flipped it every frame and rang at half the frame rate (measured: a +52.9 dB line at
 * 49.86 Hz with odd harmonics, at 44.1 kHz only, where 441 is odd). */
#define CAPTURE_OVERSAMPLE  2u

/* The oversampled read lands in g_raw, so that buffer has to hold CAPTURE_OVERSAMPLE 16-bit words
 * per wanted sample, not one. It does - g_raw is 32-bit - but assert it rather than rely on the
 * coincidence, because a short read here does not fail, it silently clamps the frame. */
_Static_assert(sizeof(g_raw) >= (size_t)SAMPLES_AT(RATE_44K) * CAPTURE_OVERSAMPLE * sizeof(int16_t),
               "g_raw too small for the oversampled read");
_Static_assert(sizeof(g_raw) >= (size_t)SAMPLES_AT(RATE_32K) * CAPTURE_OVERSAMPLE * sizeof(int16_t),
               "g_raw too small for the oversampled read");

static volatile uint32_t s_actual_rate = RATE_32K;   /* what the receiver should play at */
static volatile uint32_t s_ws_rate = RATE_32K;       /* WS as actually clocked */
static volatile bool     s_passthru;                 /* diagnostic: skip the decimation */
/* Diagnostic: force bck_div instead of letting pick_dividers choose. The same WS rate is
 * reachable with several divider pairs (64102 Hz is bck=2,clkm=39 and also bck=3,clkm=26), and
 * the two rates that differ by exactly one bit of level also differ in bck_div - so this sweeps
 * bck_div at a FIXED rate to see whether the bit alignment follows it. 0 = auto. */
static volatile uint32_t s_force_bck;

/* The SDK's i2s_set_rate() picks the two clock dividers itself, and picks them badly.
 *
 * It searches bck_div = i, clkm_div = j over 1..63 for base/32/i/j closest to the request, with
 * `abs()` applied to a float - an integer abs, so every candidate within 1 Hz ties at 0 and the
 * FIRST one found wins. For an oversampled 44.1 kHz (88200) that first hit is i=1, j=57, and
 * MEASURED: bck_div = 1 does not clock. i2s_read then times out forever ("capture dead after
 * switch" x3, i2s_to climbing, reinit looping) while 32 kHz, whose search lands on i=2, works.
 * i=3, j=19 gives the identical 87719.3 Hz with a usable divider, so the rate itself was never
 * the problem.
 *
 * So choose the pair here, require bck_div >= 2, and write it to the peripheral after the driver
 * has installed. This also makes the reported rate exact instead of a guess at what the SDK did.
 */
/* MEASURED, with a steady 1 kHz tone and the rate pinned at 32051 Hz so only the divider varied:
 * bck_div = 2 captures the mic's 24-bit word exactly ONE BIT lower in the window than bck_div >= 3
 * does, i.e. 6.13 dB quieter (one bit = 6.02 dB), repeatable to +-0.18 dB. The level was flat
 * across the spectrum and the noise floor tracked the signal, so it is a pure window shift, not
 * filtering. That alone was the long-standing "why is one rate louder" puzzle: 32 kHz landed on
 * bck_div = 2 and 44.1 kHz on 3.
 *
 * Pinning the minimum at 3 keeps BOTH rates exactly where they were (32 kHz -> bck 3, clkm 26 =
 * the same 64102.56 Hz; 44.1 kHz -> bck 3, clkm 19 = the same 87719.3 Hz) while giving them the
 * same alignment, so switching rate no longer changes loudness. Taking the louder alignment is
 * deliberate: it keeps one more of the mic word's low bits, which is the side to err on when the
 * point is to hear quiet sounds. bck_div = 1 does not clock at all (see pick_dividers). */
#define I2S_BCK_MIN  3u

/* clkm_div divides the 160 MHz base to make the I2S clock, and bck_div divides THAT to make BCK,
 * so the rate depends only on the product - but the two factors are not interchangeable. A small
 * clkm_div runs the peripheral's internal clock absurdly fast: clkm_div = 3 means 53 MHz.
 *
 * MEASURED: 88200 is reachable as bck 3 / clkm 19 (8.4 MHz internal) or bck 19 / clkm 3 (53 MHz),
 * both exactly 87719.3 Hz. An earlier version here picked the 53 MHz one by floating-point
 * accident - `base/19/3` lands a hair closer to the target than `base/3/19` because of division
 * ORDER, not because the rates differ - and 44.1 kHz then failed intermittently: capture went dead
 * at the switch (frames frozen at 4280, i2s_to climbing) and the stall detector could not recover,
 * because it kept re-initialising the same bad pair. That was the "switching rates sometimes
 * fails" fault.
 *
 * So divide ONCE by the product, so equal products compare equal, and keep only a strict
 * improvement while scanning bck ascending: the smallest bck - hence the largest clkm, hence the
 * slowest internal clock - wins every tie, deterministically. */
#define I2S_CLKM_MIN  4u

static void pick_dividers(uint32_t req, uint32_t *bck, uint32_t *clkm, uint32_t *got)
{
    float base = 160000000.0f / 32.0f;      /* the peripheral's 32-BCK frame */
    float best = 1e30f;
    uint32_t bb = I2S_BCK_MIN, bc = I2S_CLKM_MIN;
    uint32_t lo = s_force_bck ? s_force_bck : I2S_BCK_MIN;
    uint32_t hi = s_force_bck ? s_force_bck + 1u : 64u;
    for (uint32_t i = lo; i < hi; i++)
        for (uint32_t j = I2S_CLKM_MIN; j < 64; j++)
        {
            float r = base / (float)(i * j);   /* one division: (3,19) and (19,3) tie exactly */
            float d = r > (float)req ? r - (float)req : (float)req - r;
            if (d < best) { best = d; bb = i; bc = j; }
        }
    *bck = bb; *clkm = bc;
    *got = (uint32_t)(base / (float)(bb * bc) + 0.5f);
}

/* Derive the rates the receiver is told about from the dividers we actually wrote. 24-bit still
 * lands at 2/3 of its request: the divider maths assumes a 32-BCK frame and a 24-bit frame is 48
 * BCK wide, which is the whole of the old "24-bit gives 21367 Hz" mystery. */
static void publish_rates(uint32_t ws)
{
    s_ws_rate = ws;
    s_actual_rate = (s_capture_bits == 16 && !s_passthru)
                  ? ws / CAPTURE_OVERSAMPLE : ws;
}
static volatile bool     s_streaming;
static volatile uint32_t s_ctrl_pkts;

static int s_sock = -1;
static struct sockaddr_in s_dest;
static volatile bool s_dest_valid;
static volatile bool s_eassp_mode;   /* true: 16-byte EASSP audio header (Android app) */

/* ---- Roaming ----
 * The microphone is carried around, so the AP it joined at power-on is not the AP it should still
 * be on ten metres later. There is no 802.11 r/k/v in this SDK, so "roaming" can only be
 * disconnect -> scan -> rejoin, and that costs a real gap in the audio. The whole policy is
 * therefore about not doing it unless it is clearly worth the interruption:
 *
 *   - RSSI is POLLED, not event-driven. esp_wifi_sta_get_ap_info() reads the last beacon's RSSI
 *     at no radio cost, and the stats task already runs every 5 s. Using
 *     WIFI_EVENT_STA_BSS_RSSI_LOW would mean re-arming the threshold after every trigger, for no
 *     gain over a free read.
 *   - The link must be weak for ROAM_WEAK_CHECKS samples in a row, so walking past a wall does
 *     not cost a dropout.
 *   - A candidate must beat the current AP by ROAM_MARGIN_DB. Without hysteresis two similar APs
 *     make the device ping-pong, which is worse than a weak link.
 *   - A cooldown after any attempt, successful or not.
 *
 * The rejoin itself does not pick the AP: wifi_start() already sets WIFI_ALL_CHANNEL_SCAN with
 * WIFI_CONNECT_AP_BY_SIGNAL, so a plain reconnect lands on the strongest. The scan here only
 * decides WHETHER a better one exists, which keeps the choice in one place.
 */
#define ROAM_RSSI_DBM      (-70)    /* weaker than this is a candidate for moving */
#define ROAM_WEAK_CHECKS   2        /* consecutive 5 s samples: 10 s sustained */
#define ROAM_MARGIN_DB     8        /* a new AP must be this much better */
#define ROAM_COOLDOWN_MS   30000u

static volatile bool     s_roam_on = true;
static volatile uint32_t s_roams;
static uint32_t s_weak_checks;
static uint32_t s_last_roam_ms;

/* ---- Stream ownership ----
 * One listener at a time (SPEC 2.2). Both of these were MEASURED to matter, the hard way: a stray
 * test client's CONFIGURE silently stole the stream from the phone and nothing ever gave it back,
 * so every later rate change "failed" from the app's point of view while the device cheerfully
 * reported streaming=1 at 715 kbit/s - to a socket that had been closed minutes earlier. It looked
 * for all the world like a rate-switch bug. Same defect class as SPEC 5.3.
 *
 * Rules: a listener holds the stream while it keeps talking; a newcomer may take over only once
 * the holder has gone quiet; STOP is honoured only from the holder; and if the holder goes quiet
 * for good, stop streaming instead of shouting into a void. */
#define TAKEOVER_IDLE_MS   5000u    /* holder quiet this long: a newcomer may take the stream */
#define LISTENER_DEAD_MS  15000u    /* holder quiet this long: stop streaming altogether */
static volatile uint32_t s_listener_ms;      /* last time the holder was heard from */

static uint32_t now_ms(void)
{
    return (uint32_t)xTaskGetTickCount() * portTICK_PERIOD_MS;
}

static bool same_host(const struct sockaddr_in *a, const struct sockaddr_in *b)
{
    return a->sin_addr.s_addr == b->sin_addr.s_addr;
}

/* May [src] take the stream? Free, already the holder, or the holder has gone quiet. */
static bool may_take_stream(const struct sockaddr_in *src)
{
    if (!s_dest_valid || !s_streaming)
        return true;
    if (same_host(src, &s_dest))
        return true;
    return (uint32_t)(now_ms() - s_listener_ms) > TAKEOVER_IDLE_MS;
}
static uint8_t s_mac[6];

/* ---- EASSP (compatibility only) ---- */
#define EASSP_MAGIC0 0xEA
#define EASSP_MAGIC1 0x55
#define EASSP_DISCOVER  0x01
#define EASSP_CONFIGURE 0x02
#define EASSP_STOP      0x03
#define EASSP_INFO      0x81
/* The app's parseInfo() needs payload >= 34 bytes, and >= 58 to also pick up the name. */
#define EASSP_INFO_PAYLOAD 58

static uint8_t s_ctrl_tx[8 + EASSP_INFO_PAYLOAD];

/* Defined further down; the settings code drives them. */
static bool rate_is_valid(uint32_t r);
static void rate_save(uint32_t rate);
static void codec_save(uint32_t codec);
static void i2s_switch(uint32_t rate);

/* ---- Settings protocol (SPEC.md 8.2) ----
 * Self-describing on purpose: the device reports each setting's allowed values, whether it is
 * applicable right now, and when it takes effect, and the app renders its UI from that. Every
 * settings bug in the old system came from the app hardcoding ranges the firmware actually owned
 * (offering gain 0-64 when only 4-8 worked; offering gain while AGC silently ignored it; showing
 * saved values as though they were live). With the device as the single source of truth those
 * cannot recur, and a new setting appears in the UI with no app change.
 *
 *   request  GET_SETTINGS : E7 03 01
 *   reply    SETTINGS     : E7 03 02 <count> then per setting:
 *                             id u8, kind u8 (1=enum, 2=range), applicable u8,
 *                             applies_when u8 (0=now, 1=stream restart, 2=reboot),
 *                             current u32le, n u8, values[n] u32le,
 *                             then n x (vlabel_len u8, vlabel)   <- what each value is CALLED,
 *                             label_len u8, label
 *
 * The per-value labels exist because a raw value is not always self-explaining: a sample rate
 * reads fine as 44100, but a codec id reading "5" or "6" in a dropdown is useless. Naming them
 * here rather than in the app keeps the device the single source of truth - an app that mapped
 * 5 to "ADPCM" itself would be exactly the duplication this protocol exists to avoid.
 *   request  SET_SETTING  : E7 03 03 <id> <value u32le>
 *   reply    SET_RESULT   : E7 03 04 <id> <status u8> <value u32le>
 *                             status 0=ok 1=bad id 2=bad value 3=not applicable
 */
#define CTRL_MAGIC0        0xE7
#define CTRL_MAGIC1        0x03
#define MSG_GET_SETTINGS   1
#define MSG_SETTINGS       2
#define MSG_SET_SETTING    3
#define MSG_SET_RESULT     4

#define SETTING_RATE       1
#define SETTING_CODEC      2
#define SETTING_ROAM       3

/* The SETTINGS reply is ~150 bytes with three settings and their per-value labels; 256 leaves
 * room for another without silently running off the end, as put_label does not bounds check. */
static uint8_t s_set_tx[256];

static size_t put_u8(uint8_t *b, size_t o, uint8_t v) { b[o] = v; return o + 1; }
static size_t put_u32(uint8_t *b, size_t o, uint32_t v)
{
    b[o] = (uint8_t)v; b[o+1] = (uint8_t)(v >> 8);
    b[o+2] = (uint8_t)(v >> 16); b[o+3] = (uint8_t)(v >> 24);
    return o + 4;
}
static size_t put_label(uint8_t *b, size_t o, const char *t)
{
    size_t n = strlen(t);
    o = put_u8(b, o, (uint8_t)n);
    memcpy(b + o, t, n);
    return o + n;
}

static void settings_send(const struct sockaddr_in *to)
{
    size_t o = 0;
    o = put_u8(s_set_tx, o, CTRL_MAGIC0);
    o = put_u8(s_set_tx, o, CTRL_MAGIC1);
    o = put_u8(s_set_tx, o, MSG_SETTINGS);
    o = put_u8(s_set_tx, o, 3);                 /* count: only what actually works is offered */

    /* Sample rate. 48 kHz is deliberately absent (see rate_is_valid). */
    o = put_u8(s_set_tx, o, SETTING_RATE);
    o = put_u8(s_set_tx, o, 1);                 /* kind: enum */
    o = put_u8(s_set_tx, o, 1);                 /* applicable */
    o = put_u8(s_set_tx, o, 0);                 /* applies immediately (SPEC.md 8.3) */
    o = put_u32(s_set_tx, o, s_rate);
    o = put_u8(s_set_tx, o, 2);
    o = put_u32(s_set_tx, o, RATE_32K);
    o = put_u32(s_set_tx, o, RATE_44K);
    o = put_label(s_set_tx, o, "32 kHz");
    o = put_label(s_set_tx, o, "44.1 kHz");
    o = put_label(s_set_tx, o, "Sample rate");

    /* Codec. ADPCM is a quarter of the bandwidth for ~40-48 dB SNR: the trade to make when the
     * link cannot carry PCM, not an upgrade. Only the app's path is compressed - the native
     * header has no codec field, so spike_rx.py always gets PCM. */
    o = put_u8(s_set_tx, o, SETTING_CODEC);
    o = put_u8(s_set_tx, o, 1);                 /* kind: enum */
    o = put_u8(s_set_tx, o, 1);                 /* applicable */
    o = put_u8(s_set_tx, o, 0);                 /* applies immediately */
    o = put_u32(s_set_tx, o, s_codec);
    o = put_u8(s_set_tx, o, 2);
    o = put_u32(s_set_tx, o, CODEC_PCM);
    o = put_u32(s_set_tx, o, CODEC_ADPCM);
    o = put_label(s_set_tx, o, "PCM 16-bit");
    o = put_label(s_set_tx, o, "ADPCM 4-bit (1/4 rate)");
    o = put_label(s_set_tx, o, "Codec");

    /* Roaming. Worth switching off when measuring something else, since a roam is a deliberate
     * interruption. */
    o = put_u8(s_set_tx, o, SETTING_ROAM);
    o = put_u8(s_set_tx, o, 1);                 /* kind: enum */
    o = put_u8(s_set_tx, o, 1);                 /* applicable */
    o = put_u8(s_set_tx, o, 0);                 /* applies immediately */
    o = put_u32(s_set_tx, o, s_roam_on ? 1u : 0u);
    o = put_u8(s_set_tx, o, 2);
    o = put_u32(s_set_tx, o, 1);
    o = put_u32(s_set_tx, o, 0);
    o = put_label(s_set_tx, o, "On");
    o = put_label(s_set_tx, o, "Off");
    o = put_label(s_set_tx, o, "Follow strongest AP");

    sendto(s_sock, s_set_tx, o, MSG_DONTWAIT, (const struct sockaddr *)to, sizeof(*to));
}

static void setting_set(const struct sockaddr_in *to, uint8_t id, uint32_t value)
{
    uint8_t status = 1;                         /* bad id */
    if (id == SETTING_RATE)
    {
        if (!rate_is_valid(value))
        {
            status = 2;                         /* bad value */
        }
        else
        {
            if (value != s_rate)
            {
                i2s_switch(value);              /* applies at once; verifies capture came up */
                rate_save(value);
            }
            status = 0;
        }
    }
    else if (id == SETTING_ROAM)
    {
        s_roam_on = (value != 0);
        s_weak_checks = 0;
        ESP_LOGI(TAG, "roaming = %s", s_roam_on ? "on" : "off");
        status = 0;
    }
    else if (id == SETTING_CODEC)
    {
        if (value != CODEC_PCM && value != CODEC_ADPCM)
        {
            status = 2;
        }
        else
        {
            if (value != s_codec)
            {
                s_codec = value;
                s_ad_pred = 0; s_ad_index = 0;
                codec_save(value);
                ESP_LOGI(TAG, "codec = %s", value == CODEC_ADPCM ? "ADPCM" : "PCM");
            }
            status = 0;
        }
    }
    size_t o = 0;
    o = put_u8(s_set_tx, o, CTRL_MAGIC0);
    o = put_u8(s_set_tx, o, CTRL_MAGIC1);
    o = put_u8(s_set_tx, o, MSG_SET_RESULT);
    o = put_u8(s_set_tx, o, id);
    o = put_u8(s_set_tx, o, status);
    o = put_u32(s_set_tx, o, (id == SETTING_CODEC) ? s_codec
                           : (id == SETTING_ROAM)  ? (s_roam_on ? 1u : 0u)
                                                   : s_rate);
    sendto(s_sock, s_set_tx, o, MSG_DONTWAIT, (const struct sockaddr *)to, sizeof(*to));
    ESP_LOGI(TAG, "SET id=%u value=%u -> status=%u", (unsigned)id, (unsigned)value,
             (unsigned)status);
}

/* Rate enum used in both INFO and the audio header: 4 = 32000, 5 = 44100. */
static uint8_t eassp_rate_enum(uint32_t rate)
{
    return (rate == RATE_44K) ? 5 : 4;
}

/* 48 kHz was offered briefly and removed: 44.1 kHz already gives a 22 kHz Nyquist (beyond hearing),
 * the mic is only flat to 15 kHz, and 48 kHz needs a 3.09 MHz SCK against the INMP441's ~3 MHz
 * limit - so it cost airtime and sat at the edge of spec to capture nothing audible. */
static bool rate_is_valid(uint32_t r)
{
    return r == RATE_32K || r == RATE_44K;
}

/* ---- Persistence ----
 * The sample rate is a real user choice (SPEC.md 8.1), so it has to survive a power cycle. */
#define NVS_NS   "spike"
#define NVS_RATE "rate"

static void rate_save(uint32_t rate)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) != ESP_OK)
        return;
    if (nvs_set_u32(h, NVS_RATE, rate) == ESP_OK)
        nvs_commit(h);
    nvs_close(h);
}

static void codec_save(uint32_t codec)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READWRITE, &h) != ESP_OK)
        return;
    if (nvs_set_u32(h, "codec", codec) == ESP_OK)
        nvs_commit(h);
    nvs_close(h);
}
static uint32_t codec_load(uint32_t fallback)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READONLY, &h) != ESP_OK)
        return fallback;
    uint32_t v = 0;
    esp_err_t err = nvs_get_u32(h, "codec", &v);
    nvs_close(h);
    return (err == ESP_OK && (v == CODEC_PCM || v == CODEC_ADPCM)) ? v : fallback;
}
static uint32_t rate_load(uint32_t fallback)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NS, NVS_READONLY, &h) != ESP_OK)
        return fallback;
    uint32_t v = 0;
    esp_err_t err = nvs_get_u32(h, NVS_RATE, &v);
    nvs_close(h);
    return (err == ESP_OK && rate_is_valid(v)) ? v : fallback;
}

static void eassp_send_info(const struct sockaddr_in *to, uint16_t seq)
{
    memset(s_ctrl_tx, 0, sizeof(s_ctrl_tx));
    s_ctrl_tx[0] = EASSP_MAGIC0;
    s_ctrl_tx[1] = EASSP_MAGIC1;
    s_ctrl_tx[2] = 1;                      /* version */
    s_ctrl_tx[3] = EASSP_INFO;
    s_ctrl_tx[4] = (uint8_t)seq;
    s_ctrl_tx[5] = (uint8_t)(seq >> 8);
    s_ctrl_tx[6] = (uint8_t)EASSP_INFO_PAYLOAD;
    s_ctrl_tx[7] = (uint8_t)(EASSP_INFO_PAYLOAD >> 8);

    uint8_t *p = s_ctrl_tx + 8;
    uint32_t rate = s_rate;
    p[0] = s_streaming ? 1 : 0;            /* status */
    p[1] = 6;                              /* codec: 6 = PCM */
    p[3] = 1;                              /* channels */
    p[4] = (uint8_t)(rate);                /* rate, LE32 */
    p[5] = (uint8_t)(rate >> 8);
    p[6] = (uint8_t)(rate >> 16);
    p[7] = (uint8_t)(rate >> 24);
    memcpy(p + 9, s_mac, 6);               /* id */
    p[32] = 16;                            /* bits */
    p[33] = 0;                             /* transport: UDP */
    memcpy(p + 34, "esp-spike", 9);        /* name */

    sendto(s_sock, s_ctrl_tx, sizeof(s_ctrl_tx), MSG_DONTWAIT,
           (const struct sockaddr *)to, sizeof(*to));
}

static bool guards_ok(void)
{
    return g_guard0 == GUARD_MAGIC && g_guard1 == GUARD_MAGIC &&
           g_guard1b == GUARD_MAGIC && g_guard2 == GUARD_MAGIC;
}

static void guards_reset(void)
{
    g_guard0 = g_guard1 = g_guard1b = g_guard2 = GUARD_MAGIC;
}

/* ---- Wi-Fi: connect, and retry forever. Nothing clever, by design. ---- */
static void on_wifi(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg; (void)data;
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START)
    {
        esp_wifi_connect();
    }
    else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED)
    {
        ESP_LOGW(TAG, "disconnected - retrying");
        s_dest_valid = false;
        s_streaming = false;
        vTaskDelay(pdMS_TO_TICKS(1000));
        esp_wifi_connect();
    }
    else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP)
    {
        ip_event_got_ip_t *e = (ip_event_got_ip_t *)data;
        ESP_LOGI(TAG, "got ip " IPSTR, IP2STR(&e->ip_info.ip));
    }
}

static void wifi_start(void)
{
    tcpip_adapter_init();
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&cfg));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, &on_wifi, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, &on_wifi, NULL));

    wifi_config_t wc = {0};
    strncpy((char *)wc.sta.ssid, WIFI_SSID, sizeof(wc.sta.ssid) - 1);
    strncpy((char *)wc.sta.password, WIFI_PASS, sizeof(wc.sta.password) - 1);
    /* SPEC.md §4.4: fast scan joins the FIRST matching AP from channel 1 up, which in a multi-AP
     * building picks a weak one on a busy channel and produces ENOMEM floods. Scan every channel
     * and take the strongest. */
    wc.sta.scan_method = WIFI_ALL_CHANNEL_SCAN;
    wc.sta.sort_method = WIFI_CONNECT_AP_BY_SIGNAL;

    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(ESP_IF_WIFI_STA, &wc));
    ESP_ERROR_CHECK(esp_wifi_start());
    /* SPEC.md §4.4: power save MUST be off; MAX_MODEM dozes between beacons and floods sendto
     * with ENOMEM under audio load. */
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_NONE));
}

/* ---- I2S ---- */
static esp_err_t i2s_start_at(uint32_t rate)
{
    i2s_config_t cfg = {
        .mode = (i2s_mode_t)(I2S_MODE_MASTER | I2S_MODE_RX),
        /* See CAPTURE_OVERSAMPLE. The dividers get overwritten below anyway; this only
         * steers the driver's own bookkeeping. */
        .sample_rate = (int)((s_capture_bits == 16) ? rate * CAPTURE_OVERSAMPLE : rate),
        .bits_per_sample = (s_capture_bits == 24) ? I2S_BITS_PER_SAMPLE_24BIT
                                                  : I2S_BITS_PER_SAMPLE_16BIT,
        /* SPEC.md §4.1: INMP441 with L/R tied low presents on the left slot. */
        .channel_format = I2S_CHANNEL_FMT_ONLY_LEFT,
        .communication_format = I2S_COMM_FORMAT_I2S,   /* Philips */
        /* dma_buf_len counts samples; the ring is dma_buf_count * dma_buf_len * bytes_per_sample
         * bytes (i2s_create_dma_queue). The old 8 x 96 gave 1536 B, under one oversampled 44.1 kHz
         * frame (440 samples x 2 x 2 B = 1760 B), so any hiccup in the send path overran it.
         * 8 x 256 = 4096 B, about 23 ms of headroom. */
        .dma_buf_count = 8,
        .dma_buf_len = 256,
        .tx_desc_auto_clear = false,
    };
    /* Pin config goes BEFORE driver_install: that is the order the working firmware uses
     * ("set_pin before driver_install - the driver configures the GPIO matrix"), and getting it
     * backwards leaves RX unclocked, so every i2s_read just times out. */
    i2s_pin_config_t pins = {
        .bck_o_en = 0, .ws_o_en = 0,
        .bck_i_en = 1, .ws_i_en = 1,      /* receive clocks */
        .data_out_en = 0, .data_in_en = 1,
    };
    esp_err_t perr = i2s_set_pin(I2S_NUM_0, &pins);
    if (perr != ESP_OK)
        ESP_LOGW(TAG, "i2s_set_pin (pre-install): %s", esp_err_to_name(perr));

    esp_err_t err = i2s_driver_install(I2S_NUM_0, &cfg, 0, NULL);
    if (err != ESP_OK)
        return err;

    /* Again after install, in case the stock driver wants it in this order instead. */
    perr = i2s_set_pin(I2S_NUM_0, &pins);
    if (perr != ESP_OK)
        ESP_LOGW(TAG, "i2s_set_pin (post-install): %s", esp_err_to_name(perr));

    /* Overwrite the driver's divider choice with ours (see pick_dividers): its own pick can be
     * bck_div = 1, which does not clock, and that is what killed 44.1 kHz.
     *
     * i2s_driver_install() ends in i2s_set_clk(), which "set clock and start" - so by the time we
     * get here the peripheral and its DMA are ALREADY RUNNING on the driver's dividers. For an
     * oversampled 44.1 kHz those are bck_div = 1, i.e. no bit clock at all, so it has been started
     * dead; changing the dividers underneath a running peripheral then left it dead for good
     * (MEASURED: "capture dead after switch" on all 3 attempts, every attempt, with a perfectly
     * sane bck 3 / clkm 19 written - while 32 kHz survived because the driver's own pick, bck 2,
     * does clock). So stop it first and let the i2s_start() below bring it up on OUR dividers,
     * with the FIFO and DMA reset that i2s_start() performs. */
    i2s_stop(I2S_NUM_0);
    {
        uint32_t req = (s_capture_bits == 16) ? rate * CAPTURE_OVERSAMPLE : rate;
        uint32_t bck = 0, clkm = 0, ws = 0;
        pick_dividers(req, &bck, &clkm, &ws);
        if (s_capture_bits != 16)
            ws = (uint32_t)((uint64_t)ws * 2u / 3u);   /* 48-BCK frame, see publish_rates() */
        I2S0.conf.bck_div_num = bck & 0x3F;
        I2S0.conf.clkm_div_num = clkm & 0x3F;
        publish_rates(ws);
        ESP_LOGI(TAG, "i2s %u Hz req -> bck_div=%u clkm_div=%u, ws=%u Hz, sending %u Hz",
                 (unsigned)req, (unsigned)bck, (unsigned)clkm,
                 (unsigned)s_ws_rate, (unsigned)s_actual_rate);
    }

    err = i2s_start(I2S_NUM_0);
    if (err != ESP_OK)
        return err;

    /* Drain the DMA pipeline: it holds 2-3 buffers of stale data, and the INMP441 has a ~10-15 ms
     * startup transient. Small reads, and stop early if one times out. */
    {
        uint8_t flush[256];
        size_t got = 0;
        for (int i = 0; i < 6; i++)
            if (i2s_read(I2S_NUM_0, flush, sizeof(flush), &got, pdMS_TO_TICKS(100)) != ESP_OK)
                break;
    }
    return ESP_OK;
}

/* Retries, because a switch has been observed to come up with capture dead (the stall detector
 * caught it as reinit=2). Better to spend a few hundred ms here than ship a rate change that
 * sometimes silently produces no audio. */
static void i2s_switch(uint32_t rate)
{
    if (!rate_is_valid(rate))
    {
        ESP_LOGW(TAG, "ignoring invalid rate %u", (unsigned)rate);
        return;
    }
    ESP_LOGI(TAG, "switching to %u Hz", (unsigned)rate);
    for (int attempt = 1; attempt <= 3; attempt++)
    {
        i2s_stop(I2S_NUM_0);
        i2s_driver_uninstall(I2S_NUM_0);
        s_rate = rate;
        esp_err_t err = i2s_start_at(rate);
        if (err != ESP_OK)
        {
            ESP_LOGE(TAG, "i2s restart failed (attempt %d): %s", attempt, esp_err_to_name(err));
            continue;
        }
        /* Prove capture actually runs before declaring success. */
        uint8_t probe[128];
        size_t got = 0;
        if (i2s_read(I2S_NUM_0, probe, sizeof(probe), &got, pdMS_TO_TICKS(200)) == ESP_OK && got)
        {
            if (attempt > 1)
                ESP_LOGW(TAG, "rate switch needed %d attempts", attempt);
            return;
        }
        ESP_LOGW(TAG, "capture dead after switch (attempt %d) - retrying", attempt);
    }
    ESP_LOGE(TAG, "rate switch to %u Hz did not come up; stall detector will keep trying",
             (unsigned)rate);
}

/* ---- Control: non-blocking, polled from the audio loop so there is no second task ---- */
static void ctrl_poll(void)
{
    char buf[32];
    struct sockaddr_in src;
    socklen_t slen = sizeof(src);
    int n = recvfrom(s_sock, buf, sizeof(buf) - 1, MSG_DONTWAIT, (struct sockaddr *)&src, &slen);
    if (n <= 0)
        return;
    s_ctrl_pkts++;

    /* Settings protocol. */
    if (n >= 3 && (uint8_t)buf[0] == CTRL_MAGIC0 && (uint8_t)buf[1] == CTRL_MAGIC1)
    {
        uint8_t msg = (uint8_t)buf[2];
        if (msg == MSG_GET_SETTINGS)
        {
            settings_send(&src);
        }
        else if (msg == MSG_SET_SETTING && n >= 8)
        {
            uint32_t v = (uint32_t)(uint8_t)buf[4] | ((uint32_t)(uint8_t)buf[5] << 8) |
                         ((uint32_t)(uint8_t)buf[6] << 16) | ((uint32_t)(uint8_t)buf[7] << 24);
            setting_set(&src, (uint8_t)buf[3], v);
        }
        return;
    }

    /* EASSP, for the Android app. */
    if (n >= 8 && (uint8_t)buf[0] == EASSP_MAGIC0 && (uint8_t)buf[1] == EASSP_MAGIC1 &&
        (uint8_t)buf[2] == 1)
    {
        uint8_t cmd = (uint8_t)buf[3];
        uint16_t seq = (uint16_t)((uint8_t)buf[4] | ((uint8_t)buf[5] << 8));
        if (cmd == EASSP_DISCOVER)
        {
            /* Also the in-stream heartbeat: it renews the holder's lease. */
            if (s_dest_valid && same_host(&src, &s_dest))
                s_listener_ms = now_ms();
            eassp_send_info(&src, seq);
        }
        else if (cmd == EASSP_CONFIGURE && n >= 10)
        {
            /* payload = stream port, BIG-endian (the one field the old protocol byte-swaps) */
            uint16_t port = (uint16_t)(((uint8_t)buf[8] << 8) | (uint8_t)buf[9]);
            if (port != 0 && may_take_stream(&src))
            {
                s_dest = src;
                s_dest.sin_port = htons(port);
                s_dest_valid = true;
                s_eassp_mode = true;
                s_streaming = true;
                s_listener_ms = now_ms();
                ESP_LOGI(TAG, "EASSP CONFIGURE from " IPSTR " -> streaming to port %u",
                         IP2STR((ip4_addr_t *)&src.sin_addr), (unsigned)port);
            }
            else if (port != 0)
            {
                ESP_LOGW(TAG, "refusing CONFIGURE from " IPSTR ": " IPSTR " holds the stream",
                         IP2STR((ip4_addr_t *)&src.sin_addr),
                         IP2STR((ip4_addr_t *)&s_dest.sin_addr));
            }
            eassp_send_info(&src, seq);
        }
        else if (cmd == EASSP_STOP)
        {
            if (!s_dest_valid || same_host(&src, &s_dest))
            {
                s_streaming = false;
                ESP_LOGI(TAG, "EASSP STOP");
            }
            else
            {
                ESP_LOGW(TAG, "ignoring STOP from " IPSTR ": not the listener",
                         IP2STR((ip4_addr_t *)&src.sin_addr));
            }
            eassp_send_info(&src, seq);
        }
        return;
    }

    buf[n] = '\0';
    if (!strncmp(buf, "HELLO", 5))
    {
        /* Repeating HELLO is also the native heartbeat, so it renews the lease. */
        if (may_take_stream(&src))
        {
            s_dest = src;
            s_dest_valid = true;
            s_eassp_mode = false;
            s_streaming = true;
            s_listener_ms = now_ms();
            ESP_LOGI(TAG, "HELLO from " IPSTR ":%u - streaming",
                     IP2STR((ip4_addr_t *)&src.sin_addr), (unsigned)ntohs(src.sin_port));
        }
        else
        {
            ESP_LOGW(TAG, "refusing HELLO from " IPSTR ": " IPSTR " holds the stream",
                     IP2STR((ip4_addr_t *)&src.sin_addr),
                     IP2STR((ip4_addr_t *)&s_dest.sin_addr));
        }
    }
    else if (!strncmp(buf, "STOP", 4))
    {
        if (!s_dest_valid || same_host(&src, &s_dest))
        {
            s_streaming = false;
            ESP_LOGI(TAG, "STOP");
        }
        else
        {
            ESP_LOGW(TAG, "ignoring STOP from " IPSTR ": not the listener",
                     IP2STR((ip4_addr_t *)&src.sin_addr));
        }
    }
    else if (!strncmp(buf, "R32", 3))
    {
        i2s_switch(RATE_32K);
        rate_save(RATE_32K);
    }
    else if (!strncmp(buf, "R44", 3))
    {
        i2s_switch(RATE_44K);
        rate_save(RATE_44K);
    }
    else if (!strncmp(buf, "B16", 3) || !strncmp(buf, "B24", 3))
    {
        s_capture_bits = (buf[1] == '2') ? 24 : 16;
        ESP_LOGI(TAG, "capture width = %u bits", (unsigned)s_capture_bits);
        i2s_switch(s_rate);                             /* re-init at the new width */
    }
    else if (buf[0] == 'G')
    {
        /* "G32" = x32 = +30 dB. 1 = unity. Clamped to something sane. */
        int g = atoi(buf + 1);
        if (g < 1) g = 1;
        if (g > 256) g = 256;
        s_gain = (uint32_t)g;
        s_clips = 0;
        ESP_LOGI(TAG, "gain = x%d", g);
    }
    else if (buf[0] == 'D')
    {
        int d = atoi(buf + 1);
        if (d < 0 || d > 63) d = 0;
        s_force_bck = (uint32_t)d;
        ESP_LOGI(TAG, "forced bck_div = %d (0 = auto), re-initialising", d);
        i2s_switch(s_rate);
    }
    else if (buf[0] == 'P')
    {
        /* Diagnostic. "P1" sends the oversampled stream undecimated so the 16-32 kHz half can be
         * inspected: if it mirrors 0-16 kHz the samples really are duplicates, and if it carries
         * its own noise floor then the decimation is aliasing and needs rethinking. Frames are
         * halved in passthrough so the packet buffer and MTU are unchanged; packet rate doubles. */
        s_passthru = (buf[1] == '1');
        publish_rates(s_ws_rate);
        ESP_LOGI(TAG, "passthrough = %d, sending %u Hz",
                 (int)s_passthru, (unsigned)s_actual_rate);
    }
}

/* Scans for a better AP on our SSID and rejoins if one is clearly stronger. Blocking, ~1-2 s,
 * and it interrupts audio - only called once the link has already been poor for a while. */
static void roam_try(const wifi_ap_record_t *cur)
{
    wifi_scan_config_t sc;
    memset(&sc, 0, sizeof(sc));
    sc.ssid = (uint8_t *)WIFI_SSID;          /* only our own network is a candidate */
    sc.scan_type = WIFI_SCAN_TYPE_ACTIVE;
    /* Short dwell: the docs warn that over 1500 ms per channel can itself drop the link, and a
     * long scan is a long hole in the audio. */
    sc.scan_time.active.min = 60;
    sc.scan_time.active.max = 120;

    s_last_roam_ms = now_ms();
    if (esp_wifi_scan_start(&sc, true) != ESP_OK)
    {
        ESP_LOGW(TAG, "roam: scan failed");
        return;
    }
    uint16_t n = 0;
    esp_wifi_scan_get_ap_num(&n);
    if (n == 0)
        return;
    if (n > 8)
        n = 8;
    wifi_ap_record_t recs[8];
    /* Always call this even if we ignore the result: it frees the driver's scan buffer. */
    if (esp_wifi_scan_get_ap_records(&n, recs) != ESP_OK)
        return;

    int best = -127, bi = -1;
    for (int i = 0; i < (int)n; i++)
    {
        if (memcmp(recs[i].bssid, cur->bssid, 6) == 0)
            continue;                         /* that is where we already are */
        if (recs[i].rssi > best) { best = recs[i].rssi; bi = i; }
    }
    if (bi < 0)
    {
        ESP_LOGI(TAG, "roam: %d dBm, no other AP on this SSID", (int)cur->rssi);
        return;
    }
    if (best < (int)cur->rssi + ROAM_MARGIN_DB)
    {
        ESP_LOGI(TAG, "roam: staying put (%d dBm; best alternative %d, needs %d)",
                 (int)cur->rssi, best, (int)cur->rssi + ROAM_MARGIN_DB);
        return;
    }
    ESP_LOGW(TAG, "roam: %d dBm -> %02x%02x%02x%02x%02x%02x at %d dBm, rejoining",
             (int)cur->rssi, recs[bi].bssid[0], recs[bi].bssid[1], recs[bi].bssid[2],
             recs[bi].bssid[3], recs[bi].bssid[4], recs[bi].bssid[5], best);
    s_roams++;
    esp_wifi_disconnect();                    /* the reconnect picks by signal, see wifi_start */
    esp_wifi_connect();
}

/* ---- The whole audio path: read I2S, packetise, send. One task, no hand-offs. ---- */
static void audio_task(void *arg)
{
    (void)arg;
    uint32_t seq = 0;
    while (1)
    {
        ctrl_poll();

        /* MEASURED: i2s_read sometimes fails from boot onwards (frames stop, timeouts climb) and a
         * driver uninstall+reinstall cures it immediately. Cause unclear - most likely the init
         * racing Wi-Fi bring-up - so rather than guess, recover. Done here, never from another
         * task, so we are not uninstalling under our own blocked read. */
        if (s_i2s_reinit_req)
        {
            s_i2s_reinit_req = false;
            s_i2s_reinits++;
            ESP_LOGW(TAG, "i2s appears stalled - reinitialising (#%u)", (unsigned)s_i2s_reinits);
            i2s_switch(s_rate);
        }

        uint32_t rate = s_rate;
        size_t want_samples = (size_t)rate * FRAME_MS / 1000u;
        if (want_samples > MAX_SAMPLES)
            want_samples = MAX_SAMPLES;          /* cannot happen; belt and braces */
        /* MUST be even. In 16-bit mode the DMA packs two samples per 32-bit word and the de-swap
         * below pairs from the start of the frame, so an odd frame length leaves every read ending
         * mid-pair: the alignment then flips frame to frame and half the audio is re-scrambled.
         * Measured as a 100 Hz artifact ~5 dB above a pink-noise control at 44.1 kHz (441 samples,
         * odd) and absent at 32 kHz (320, even). Rounding down costs 23 us of frame length. */
        /* Keep it even so the oversampled read is a whole number of 32-bit DMA words. */
        want_samples &= ~(size_t)1;
        /* Passthrough sends twice as many samples per frame, so halve the frame to keep the
         * packet the same size. */
        if (s_passthru)
            want_samples /= 2u;

        uint32_t cbits = s_capture_bits;
        /* 16-bit: read CAPTURE_OVERSAMPLE 16-bit words per wanted sample (see
         * CAPTURE_OVERSAMPLE). 24-bit: one left-justified sample per 32-bit word. */
        size_t want_bytes = (cbits == 24)
                          ? want_samples * 4u
                          : want_samples * CAPTURE_OVERSAMPLE * sizeof(int16_t);

        size_t got = 0;
        esp_err_t err = i2s_read(I2S_NUM_0, (void *)g_raw, want_bytes, &got,
                                 /* Never below a couple of FreeRTOS ticks, whatever FRAME_MS is. */
                                 pdMS_TO_TICKS(FRAME_MS * 4 < 20 ? 20 : FRAME_MS * 4));
        if (err != ESP_OK || got == 0)
        {
            s_i2s_timeouts++;
            continue;
        }
        if (got < want_bytes)
            s_short_reads++;
        s_frames++;

        size_t nsamp;
        if (cbits == 24)
        {
            nsamp = got / 4u;
            for (size_t i = 0; i < nsamp; i++)
                g_samples[i] = (int16_t)(g_raw[i] >> 16);
        }
        else
        {
            /* Take every other word; the ones dropped are the delayed copies. */
            int16_t *raw = (int16_t *)g_raw;
            size_t nraw = got / sizeof(int16_t);
            if (nraw > 3)
            {
                size_t m = 0;
                for (size_t i = 3; i < nraw; i++)
                    if (raw[i] == raw[i - 3]) m++;
                s_dup_pairs += (nraw - 3) / 2u;   /* only half the positions can match */
                s_dup_match += m;
            }

            if (s_passthru)
            {
                nsamp = nraw;                       /* already in g_raw, just reinterpret */
                for (size_t i = 0; i < nraw; i++)
                    g_samples[i] = raw[i];
            }
            else
            {
                nsamp = 0;
                for (size_t i = 0; i < nraw; i += CAPTURE_OVERSAMPLE)
                    g_samples[nsamp++] = raw[i];
            }
        }
        got = nsamp * sizeof(int16_t);                  /* from here on: 16-bit samples */

        /* Apply gain in 32-bit and saturate back into int16. */
        uint32_t gain = s_gain;
        if (gain != 1)
        {
            size_t n = got / sizeof(int16_t);
            uint32_t clipped = 0;
            for (size_t i = 0; i < n; i++)
            {
                int32_t v = (int32_t)g_samples[i] * (int32_t)gain;
                if (v > 32767) { v = 32767; clipped++; }
                else if (v < -32768) { v = -32768; clipped++; }
                g_samples[i] = (int16_t)v;
            }
            if (clipped)
                s_clips += clipped;
        }

        if (!guards_ok())
        {
            s_guard_hits++;
            guards_reset();
        }

        if (!s_streaming || !s_dest_valid)
            continue;

        size_t hdr;
        if (s_eassp_mode)
        {
            /* EASSP audio header the app parses: seq LE16 at 0, codec at 6, rate enum at 7,
             * channels at 8, bits LE16 at 14, samples from 16. */
            hdr = 16;
            memset(g_packet, 0, hdr);
            g_packet[0] = (uint8_t)(seq);
            g_packet[1] = (uint8_t)(seq >> 8);
            g_packet[6] = (uint8_t)s_codec;
            g_packet[7] = eassp_rate_enum(rate);
            g_packet[8] = 1;                          /* channels */
            g_packet[14] = (s_codec == CODEC_ADPCM) ? 4 : 16;   /* bits LE16 */
            g_packet[15] = 0;
        }
        else
        {
            hdr = HDR_BYTES;
            g_packet[0] = 0xE7;
            g_packet[1] = 0x02;
            g_packet[2] = (uint8_t)cbits;               /* capture width, 16 or 24 */
            g_packet[3] = 16;                           /* transmitted width */
            g_packet[4] = (uint8_t)(seq);
            g_packet[5] = (uint8_t)(seq >> 8);
            g_packet[6] = (uint8_t)(seq >> 16);
            g_packet[7] = (uint8_t)(seq >> 24);
            {   /* actual hardware rate, so the receiver never infers it */
                uint32_t ar = s_actual_rate;
                g_packet[8]  = (uint8_t)(ar);
                g_packet[9]  = (uint8_t)(ar >> 8);
                g_packet[10] = (uint8_t)(ar >> 16);
                g_packet[11] = (uint8_t)(ar >> 24);
            }
        }
        size_t body;
        if (s_eassp_mode && s_codec == CODEC_ADPCM)
            body = adpcm_encode(g_samples, got / sizeof(int16_t), g_packet + hdr);
        else
            { memcpy(g_packet + hdr, g_samples, got); body = got; }
        seq++;

        int sent = sendto(s_sock, g_packet, hdr + body, MSG_DONTWAIT,
                          (struct sockaddr *)&s_dest, sizeof(s_dest));
        if (sent < 0)
        {
            /* SPEC.md §4.4: ENOMEM here is local congestion, not a dead link. Count it, keep going;
             * never treat it as a reason to tear the connection down. */
            s_send_fail++;
            s_last_errno = (uint32_t)errno;
        }
        else
        {
            s_packets++;
            s_tx_bytes += (uint32_t)sent;
        }
    }
}

static void stats_task(void *arg)
{
    (void)arg;
    uint32_t last_pkts = 0, last_frames = 0, last_to = 0, last_bytes = 0;
    while (1)
    {
        vTaskDelay(pdMS_TO_TICKS(5000));

        /* Listener watchdog. Without this the device streamed 715 kbit/s to a socket that had
         * been closed minutes earlier, because nothing ever revoked the destination. Both
         * listeners heartbeat while they want audio (EASSP DISCOVER, or a repeated HELLO), so
         * silence this long means nobody is there. */
        if (s_streaming && (uint32_t)(now_ms() - s_listener_ms) > LISTENER_DEAD_MS)
        {
            ESP_LOGW(TAG, "listener " IPSTR " silent for %u ms - stopping the stream",
                     IP2STR((ip4_addr_t *)&s_dest.sin_addr),
                     (unsigned)(now_ms() - s_listener_ms));
            s_streaming = false;
        }

        /* Stall detector: frames not advancing while timeouts are, means capture is dead. */
        uint32_t f = s_frames, to = s_i2s_timeouts;
        if (f == last_frames && to > last_to)
            s_i2s_reinit_req = true;
        last_frames = f; last_to = to;
        uint32_t pkts = s_packets;
        uint32_t dp = pkts - last_pkts;
        last_pkts = pkts;
        /* kbit/s over the last 5 s, payload+header as actually sent */
        /* Bytes actually handed to sendto, not a guess from the PCM frame size: the old estimate
         * could not see a codec change at all, and a statistic that cannot detect a 3.4x drop in
         * bandwidth is worse than none. */
        uint32_t bytes = s_tx_bytes;
        uint32_t kbps = ((bytes - last_bytes) * 8u) / 5000u;
        last_bytes = bytes;

        wifi_ap_record_t ap;
        memset(&ap, 0, sizeof(ap));
        bool have_ap = (esp_wifi_sta_get_ap_info(&ap) == ESP_OK);
        ESP_LOGI(TAG,
                 "rate=%u(actual %u) cap=%ub gain=x%u clips=%u streaming=%d frames=%u pkts=%u (%u kbit/s) "
                 "send_fail=%u errno=%u "
                 "i2s_to=%u short=%u reinit=%u dup=%u%% GUARD=%u heap=%u min=%u ctrl=%u "
                 "rssi=%d ch=%u ap=%02x%02x%02x%02x%02x%02x roams=%u",
                 (unsigned)s_rate, (unsigned)s_actual_rate, (unsigned)s_capture_bits,
                 (unsigned)s_gain, (unsigned)s_clips,
                 (int)s_streaming, (unsigned)s_frames, (unsigned)pkts,
                 (unsigned)kbps, (unsigned)s_send_fail, (unsigned)s_last_errno,
                 (unsigned)s_i2s_timeouts, (unsigned)s_short_reads, (unsigned)s_i2s_reinits,
                 (unsigned)(s_dup_pairs ? (uint32_t)((uint64_t)s_dup_match * 100u / s_dup_pairs) : 0),
                 (unsigned)s_guard_hits,
                 (unsigned)esp_get_free_heap_size(), (unsigned)esp_get_minimum_free_heap_size(),
                 (unsigned)s_ctrl_pkts,
                 /* Which AP we are on and how strong it is. Free to read - it is the last beacon's
                  * RSSI, not a scan - and it is the input any roaming policy would need. Without
                  * it there is no way to tell a weak link from a busy one. */
                 (int)ap.rssi, (unsigned)ap.primary,
                 ap.bssid[0], ap.bssid[1], ap.bssid[2], ap.bssid[3], ap.bssid[4], ap.bssid[5],
                 (unsigned)s_roams);

        /* Roaming decision, on the sample we just logged. */
        if (have_ap && s_roam_on && ap.rssi < ROAM_RSSI_DBM)
            s_weak_checks++;
        else
            s_weak_checks = 0;
        if (s_weak_checks >= ROAM_WEAK_CHECKS &&
            (uint32_t)(now_ms() - s_last_roam_ms) > ROAM_COOLDOWN_MS)
        {
            s_weak_checks = 0;
            roam_try(&ap);
        }
    }
}

void app_main(void)
{
    ESP_LOGI(TAG, "=== esp-tone v2 throughput spike ===");
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND)
    {
        nvs_flash_erase();
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    /* Restore the saved rate before starting capture. */
    s_rate = rate_load(RATE_32K);
    s_codec = codec_load(CODEC_PCM);
    ESP_LOGI(TAG, "sample rate %u Hz (from NVS)", (unsigned)s_rate);

    /* I2S first, before Wi-Fi bring-up: the observed boot-time capture stall looks like the init
     * racing Wi-Fi, and the self-heal above is the safety net if it still happens. */
    err = i2s_start_at(s_rate);
    if (err != ESP_OK)
    {
        ESP_LOGE(TAG, "i2s init failed: %s", esp_err_to_name(err));
        return;
    }

    wifi_start();
    esp_wifi_get_mac(ESP_IF_WIFI_STA, s_mac);
    /* SPEC.md §4.1: the first DMA read after init returns saturated garbage - throw it away. */
    {
        size_t got = 0;
        i2s_read(I2S_NUM_0, g_samples, sizeof(g_samples), &got, pdMS_TO_TICKS(200));
        ESP_LOGI(TAG, "discarded first read (%u bytes)", (unsigned)got);
    }

    s_sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s_sock < 0)
    {
        ESP_LOGE(TAG, "socket failed: errno=%d", errno);
        return;
    }
    struct sockaddr_in me = {0};
    me.sin_family = AF_INET;
    me.sin_addr.s_addr = htonl(INADDR_ANY);
    me.sin_port = htons(CTRL_PORT);
    if (bind(s_sock, (struct sockaddr *)&me, sizeof(me)) < 0)
    {
        ESP_LOGE(TAG, "bind failed: errno=%d", errno);
        return;
    }
    ESP_LOGI(TAG, "listening on udp/%d - send HELLO to start", CTRL_PORT);

    xTaskCreate(stats_task, "stats", 2048, NULL, 3, NULL);
    xTaskCreate(audio_task, "audio", 3072, NULL, 10, NULL);
}
