// Flash chip / power diagnostic for ESP8266.
// Prints chip + flash info, supply voltage, then repeatedly erases, writes and
// verifies a sector in unused flash while tracking the lowest VCC seen.

ADC_MODE(ADC_VCC);

const uint32_t TEST_ADDR = 0x80000;   // well past the sketch, inside the 1MB part
const int      ROUNDS    = 20;

uint32_t buf[1024];   // 4 KB sector

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println("=== flash_test ===");
  Serial.printf("Chip ID:        %06X\n", ESP.getChipId());
  Serial.printf("Flash JEDEC ID: %06X\n", ESP.getFlashChipId());
  Serial.printf("Flash real:     %u bytes\n", ESP.getFlashChipRealSize());
  Serial.printf("Flash config:   %u bytes\n", ESP.getFlashChipSize());
  Serial.printf("Flash speed:    %u Hz\n", ESP.getFlashChipSpeed());
  Serial.printf("Sketch size:    %u bytes\n", ESP.getSketchSize());
  Serial.printf("Reset reason:   %s\n", ESP.getResetReason().c_str());
  Serial.printf("VCC idle:       %u mV\n", ESP.getVcc());
}

void loop() {
  uint32_t minVcc = 99999;
  int bad = 0;
  for (int r = 0; r < ROUNDS; r++) {
    for (int i = 0; i < 1024; i++) buf[i] = 0xA5A50000u ^ (r << 12) ^ i;
    uint32_t sec = TEST_ADDR / 4096;
    bool ok = ESP.flashEraseSector(sec);
    uint32_t v1 = ESP.getVcc();
    ok = ok && ESP.flashWrite(TEST_ADDR, buf, sizeof(buf));
    uint32_t v2 = ESP.getVcc();
    uint32_t rd[1024];
    ok = ok && ESP.flashRead(TEST_ADDR, rd, sizeof(rd));
    ok = ok && memcmp(buf, rd, sizeof(buf)) == 0;
    if (v1 < minVcc) minVcc = v1;
    if (v2 < minVcc) minVcc = v2;
    if (!ok) bad++;
    yield();
  }
  Serial.printf("rounds=%d failures=%d minVCC=%u mV  uptime=%lu ms\n",
                ROUNDS, bad, minVcc, millis());
  delay(2000);
}
