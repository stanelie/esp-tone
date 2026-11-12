#include <ESP8266WiFi.h>
#include <ESP8266mDNS.h>

// WiFi Configuration
#include "wifi_config.h"            // local + gitignored; see wifi_config.h.example
const char* ssid     = WIFI_SSID;
const char* password = WIFI_PASS;

// Audio & Network Parameters
const char*    hostName   = "esp-tone";   // reachable as esp-tone.local
const uint16_t port       = 1234;
const uint32_t sampleRate = 16000; // 16 kHz
const uint16_t sineFreq   = 440;   // A4 tone

// The player connects to http://<esp-ip>:1234 and receives an endless WAV stream.
// GET /info returns a one-line JSON description and closes (used for discovery).
WiFiServer server(port);
WiFiClient player;

// 256 samples = 16 ms of audio, 16-bit signed little-endian (WAV)
const int SAMPLES_PER_PACKET = 256;
uint8_t packetBuffer[SAMPLES_PER_PACKET * 2];

float phase = 0.0;
const float phaseIncrement = (2.0 * 3.14159265 * sineFreq) / sampleRate;

uint32_t nextSendUs = 0;

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
  put32(h + 24, sampleRate);
  put32(h + 28, sampleRate * 2);
  put32(h + 40, 0xFFFFFFFF);
  c.write(h, sizeof(h));
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
             "\"bits\":16,\"freq\":%u,\"busy\":%s}\n",
             hostName, ESP.getChipId(), (unsigned)sampleRate, sineFreq,
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
  nextSendUs = micros();
  Serial.println("Player connected");
}

void setup() {
  Serial.begin(115200);
  delay(10);

  Serial.println();
  Serial.print("Connecting to ");
  Serial.println(ssid);

  WiFi.mode(WIFI_STA);
  WiFi.setSleepMode(WIFI_NONE_SLEEP);   // modem sleep makes the ESP miss mDNS queries
  WiFi.hostname(hostName);
  WiFi.begin(ssid, password);

  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }

  Serial.println("\nWiFi connected!");
  Serial.print("ESP8266 IP Address: ");
  Serial.println(WiFi.localIP());

  server.begin();

  // Advertise via mDNS/DNS-SD so players can find us without knowing the IP
  if (MDNS.begin(hostName)) {
    MDNS.addService("esptone", "tcp", port);
    MDNS.addServiceTxt("esptone", "tcp", "id", String(ESP.getChipId(), HEX));
    MDNS.addServiceTxt("esptone", "tcp", "format", "wav");
    MDNS.addServiceTxt("esptone", "tcp", "rate", String(sampleRate));
    MDNS.addServiceTxt("esptone", "tcp", "channels", "1");
    Serial.printf("mDNS: %s.local, service _esptone._tcp port %u\n", hostName, port);
  } else {
    Serial.println("mDNS failed to start");
  }
  Serial.printf("Open http://%s:%u in your audio player\n",
                WiFi.localIP().toString().c_str(), port);
}

void loop() {
  if (WiFi.status() != WL_CONNECTED) return;

  MDNS.update();

  WiFiClient c = server.available();
  if (c) handleNewClient(c);

  if (!player || !player.connected()) return;

  // Pace to real time: 256 samples / 16000 Hz = 16 ms per block
  if ((int32_t)(micros() - nextSendUs) < 0) return;
  nextSendUs += (uint32_t)SAMPLES_PER_PACKET * 1000000UL / sampleRate;

  int idx = 0;
  for (int i = 0; i < SAMPLES_PER_PACKET; i++) {
    int16_t sample = (int16_t)(sin(phase) * 10000.0); // Amplitude ~10000
    phase += phaseIncrement;
    if (phase >= 2.0 * 3.14159265) {
      phase -= 2.0 * 3.14159265;
    }
    packetBuffer[idx++] = (uint8_t)(sample & 0xFF);        // little-endian
    packetBuffer[idx++] = (uint8_t)((sample >> 8) & 0xFF);
  }

  if (player.write(packetBuffer, sizeof(packetBuffer)) == 0) {
    Serial.println("Player disconnected");
    player.stop();
  }
}
