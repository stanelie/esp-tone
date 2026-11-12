#include <ESP8266WiFi.h>
#include <ESP8266mDNS.h>
#include "mic_i2s.h"   // the core's I2S driver with 64 ms of DMA buffering instead of 16 ms

// Streams an INMP441 I2S microphone with the same protocol as test_tone, so the Android app and
// tone_player.py work unchanged.
//
// Wiring (the ESP8266 I2S input pins are fixed in hardware):
//   INMP441 SCK -> GPIO13 (MTCK, chip pin 12)
//   INMP441 WS  -> GPIO14 (MTMS, chip pin 9)
//   INMP441 SD  -> GPIO12 (MTDI, chip pin 10)
//   INMP441 L/R -> GND (left channel), VDD -> 3.3 V, GND -> GND
//
// The I2S runs in the ESP8266's 24-bit mode (24 clocks per channel), which is what other
// ESP8266 + INMP441 projects use. The mic sends 24 bits per sample; the top bits, after a gain,
// become the 16-bit samples that are streamed.

// WiFi Configuration
#include "wifi_config.h"            // local + gitignored; see wifi_config.h.example
const char* ssid     = WIFI_SSID;
const char* password = WIFI_PASS;

// Audio & Network Parameters
const char*    hostName   = "esp-mic";    // reachable as esp-mic.local
const uint16_t port       = 1234;
const uint32_t sampleRate = 16000;        // 16 kHz requested
// What the I2S really runs at (integer dividers from 160 MHz: about 16026 Hz). This is the rate
// announced to players, so they drain the stream exactly as fast as the mic fills it.
uint32_t streamRate = sampleRate;

// Gain applied to the 24-bit sample before it is cut to 16 bits, as a shift: 16 bits would be
// a shift of 8, every step less doubles the volume (+6 dB). Speech at a normal distance is
// quiet at full scale, so start at 4 (+24 dB) and adjust.
const int gainShift = 4;

// Which of the two I2S slots carries the mic: -1 = pick automatically at boot (the slot that is
// not driven reads almost constant), 0 or 1 to force one.
const int forceSlot = -1;

// The player connects to http://<esp-ip>:1234 and receives an endless WAV stream.
// GET /info returns a one-line JSON description and closes (used for discovery).
WiFiServer server(port);
WiFiClient player;

// Samples from the mic wait here until the network takes them. The I2S DMA holds 64 ms (see
// mic_i2s.cpp; the stock core holds 16 ms, and Wi-Fi stalls longer than that dropped about 1.4% of
// the samples, heard as crackling), so it is drained every loop whether or not anyone is listening.
const int RING_SAMPLES = 4096;            // 256 ms
int16_t ring[RING_SAMPLES];
uint32_t ringHead = 0, ringTail = 0;      // free-running counters
uint32_t overruns = 0;

int micSlot = 0;
int32_t dcLevel = 0;                      // running DC estimate, 24-bit units << 4
int32_t peak = 0;                         // peak 16-bit level since the last report

void put32(uint8_t* p, uint32_t v) {
  p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
}

// Standard 44-byte PCM WAV header with the "unknown length" size fields
// (0xFFFFFFFF) so players treat it as a live stream.
void sendWavHeader(WiFiClient& c) {
  uint8_t h[44] = {
    'R','I','F','F', 0,0,0,0, 'W','A','V','E',
    'f','m','t',' ', 16,0,0,0, 1,0, 1,0,
    0,0,0,0, 0,0,0,0, 2,0, 16,0,
    'd','a','t','a', 0,0,0,0
  };
  put32(h + 4, 0xFFFFFFFF);
  put32(h + 24, streamRate);
  put32(h + 28, streamRate * 2);
  put32(h + 40, 0xFFFFFFFF);
  c.write(h, sizeof(h));
}

// One 32-bit DMA word = one channel's sample, 24 bits in bits 31..8.
bool readWord(int32_t* w) {
  int16_t lo, hi;
  if (!mi2s_read_sample(&lo, &hi, false)) return false;
  *w = (int32_t)(((uint32_t)(uint16_t)hi << 16) | (uint16_t)lo);
  return true;
}

// Measure both slots for a moment and keep the one that moves: the mic drives one slot and
// leaves SD floating in the other, which then reads almost constant.
int detectSlot() {
  const int N = 4000;
  int64_t sum[2] = {0, 0};
  int64_t dev[2] = {0, 0};
  int32_t first[2] = {0, 0};
  int n = 0, i = 0;
  uint32_t t0 = millis();
  while (n < N * 2 && millis() - t0 < 2000) {
    int32_t w;
    if (!readWord(&w)) { yield(); continue; }
    int s = i++ & 1;
    int32_t v = w >> 8;
    if (n < 2) first[s] = v;
    sum[s] += v;
    dev[s] += abs(v - first[s]);
    n++;
  }
  Serial.printf("Slot activity: slot0=%lld slot1=%lld (%d words)\n",
                dev[0] / N, dev[1] / N, n);
  if (dev[0] == 0 && dev[1] == 0) Serial.println("No signal on either slot: check the mic wiring");
  return dev[1] > dev[0] ? 1 : 0;
}

// Move everything the I2S has captured into the ring, as 16-bit samples.
void drainMic() {
  static int slot = 0;
  int32_t w;
  while (readWord(&w)) {
    int s = slot;
    slot ^= 1;
    if (s != micSlot) continue;

    int32_t v = w >> 8;                       // signed 24-bit sample
    dcLevel += ((v << 4) - dcLevel) >> 10;    // slow DC tracker (removes the mic's offset)
    v -= dcLevel >> 4;
    v >>= (8 - gainShift);
    if (v > 32767) v = 32767;
    if (v < -32768) v = -32768;
    if (abs(v) > peak) peak = abs(v);

    if (ringHead - ringTail >= RING_SAMPLES) { ringTail++; overruns++; }   // drop oldest
    ring[ringHead++ % RING_SAMPLES] = (int16_t)v;
  }
}

// Send as much of the ring as the connection takes without blocking, so the mic is drained in time.
void sendToPlayer() {
  static uint8_t buf[512];
  while (ringHead != ringTail) {
    size_t room = player.availableForWrite();
    if (room < 64) return;
    size_t n = ringHead - ringTail;
    if (n > sizeof(buf) / 2) n = sizeof(buf) / 2;
    if (n > room / 2) n = room / 2;
    for (size_t i = 0; i < n; i++) {
      int16_t s = ring[(ringTail + i) % RING_SAMPLES];
      buf[2 * i] = s & 0xFF;                  // little-endian
      buf[2 * i + 1] = (s >> 8) & 0xFF;
    }
    if (player.write(buf, n * 2) == 0) {
      Serial.println("Player disconnected");
      player.stop();
      return;
    }
    ringTail += n;
  }
}

// Read the HTTP request line, discard the remaining headers.
String readRequest(WiFiClient& c) {
  String requestLine;
  uint32_t t0 = millis();
  bool first = true;
  while (c.connected() && millis() - t0 < 1000) {
    if (c.available()) {
      String line = c.readStringUntil('\n');
      if (first) { requestLine = line; first = false; }
      if (line.length() <= 1) break;   // blank line ends the headers
    } else {
      drainMic();
      delay(1);
    }
  }
  return requestLine;
}

void handleNewClient(WiFiClient c) {
  c.setNoDelay(true);
  String req = readRequest(c);

  if (req.indexOf(" /info") >= 0) {
    c.print("HTTP/1.0 200 OK\r\n"
            "Content-Type: application/json\r\n"
            "Connection: close\r\n\r\n");
    c.printf("{\"name\":\"%s\",\"id\":\"%06x\",\"format\":\"wav\",\"rate\":%u,\"channels\":1,"
             "\"bits\":16,\"source\":\"mic\",\"busy\":%s}\n",
             hostName, ESP.getChipId(), (unsigned)streamRate,
             (player && player.connected()) ? "true" : "false");
    c.stop();
    return;
  }

  if (player && player.connected()) {   // one player at a time
    if (c.remoteIP() == player.remoteIP()) {
      // The same listener is back (e.g. after a Wi-Fi drop). Its old connection is stale but
      // still looks open until TCP times out, so replace it instead of refusing.
      player.stop();
    } else {
      c.print("HTTP/1.0 503 Busy\r\nConnection: close\r\n\r\n");
      c.stop();
      return;
    }
  }

  c.print("HTTP/1.0 200 OK\r\n"
          "Content-Type: audio/wav\r\n"
          "Cache-Control: no-cache\r\n"
          "Connection: close\r\n\r\n");
  sendWavHeader(c);
  player = c;
  ringTail = ringHead;                  // start live, not with old audio
  Serial.println("Player connected");
}

void setup() {
  Serial.begin(115200);
  delay(10);
  Serial.println();

  mi2s_set_bits(24);                    // must come before begin
  if (!mi2s_rxtx_begin(true, false)) {  // receive only, ESP drives SCK and WS
    Serial.println("I2S start failed");
  }
  mi2s_set_rate(sampleRate);
  streamRate = (uint32_t)(mi2s_get_real_rate() + 0.5f);
  Serial.printf("I2S: requested %u Hz, actual %u Hz\n", (unsigned)sampleRate, (unsigned)streamRate);
  delay(100);                           // the INMP441 needs ~85 ms to start up

  micSlot = forceSlot >= 0 ? forceSlot : detectSlot();
  Serial.printf("Using slot %d\n", micSlot);

  Serial.print("Connecting to ");
  Serial.println(ssid);

  WiFi.mode(WIFI_STA);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);   // modem sleep makes the ESP miss mDNS queries
  WiFi.hostname(hostName);
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    for (int i = 0; i < 50; i++) { drainMic(); delay(10); }
    Serial.print(".");
  }

  Serial.println("\nWiFi connected!");
  Serial.print("ESP8266 IP Address: ");
  Serial.println(WiFi.localIP());

  server.begin();

  // Advertise via mDNS/DNS-SD so players can find us without knowing the IP. Same service
  // type as the tone transmitter, so existing players find it.
  if (MDNS.begin(hostName)) {
    MDNS.addService("esptone", "tcp", port);
    MDNS.addServiceTxt("esptone", "tcp", "id", String(ESP.getChipId(), HEX));
    MDNS.addServiceTxt("esptone", "tcp", "format", "wav");
    MDNS.addServiceTxt("esptone", "tcp", "rate", String(streamRate));
    MDNS.addServiceTxt("esptone", "tcp", "channels", "1");
    MDNS.addServiceTxt("esptone", "tcp", "source", "mic");
    Serial.printf("mDNS: %s.local, service _esptone._tcp port %u\n", hostName, port);
  } else {
    Serial.println("mDNS failed to start");
  }
  Serial.printf("Open http://%s:%u in your audio player\n",
                WiFi.localIP().toString().c_str(), port);
}

void loop() {
  drainMic();
  if (WiFi.status() != WL_CONNECTED) return;

  MDNS.update();
  drainMic();

  WiFiClient c = server.available();
  if (c) handleNewClient(c);

  if (player && player.connected()) {
    sendToPlayer();
  } else {
    ringTail = ringHead;                // nobody listening: discard
  }

  // Level meter on the serial port, every 2 s: shows the mic is alive and whether the gain fits.
  static uint32_t lastReport = 0;
  if (millis() - lastReport >= 2000) {
    lastReport = millis();
    Serial.printf("level peak=%ld (of 32767) overruns=%lu\n", (long)peak, (unsigned long)overruns);
    peak = 0;
  }
}
