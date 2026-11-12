// The parts of the bundled I2S driver (mic_i2s.cpp) that mic_stream uses. Same behaviour as the
// core's i2s_* functions, with 64 ms of DMA buffering instead of 16 ms.
#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

bool mi2s_set_bits(int bits);
bool mi2s_rxtx_begin(bool enableRx, bool enableTx);
bool mi2s_rxtxdrive_begin(bool enableRx, bool enableTx, bool driveRxClocks, bool driveTxClocks);
void mi2s_begin(void);
void mi2s_end(void);
void mi2s_set_rate(uint32_t rate);
void mi2s_set_dividers(uint8_t div1, uint8_t div2);
float mi2s_get_real_rate(void);
bool mi2s_read_sample(int16_t *left, int16_t *right, bool blocking);
bool mi2s_rx_is_full(void);
bool mi2s_rx_is_empty(void);
uint16_t mi2s_rx_available(void);
void mi2s_rx_set_callback(void (*callback)(void));
// TX side, unused by mic_stream but defined in the copied driver.
bool mi2s_is_full(void);
bool mi2s_is_empty(void);
uint16_t mi2s_available(void);
void mi2s_set_callback(void (*callback)(void));
bool mi2s_write_sample(uint32_t sample);
bool mi2s_write_sample_nb(uint32_t sample);
bool mi2s_write_lr(int16_t left, int16_t right);
uint16_t mi2s_write_buffer_mono_nb(const int16_t *frames, uint16_t frame_count);
uint16_t mi2s_write_buffer_mono(const int16_t *frames, uint16_t frame_count);
uint16_t mi2s_write_buffer_nb(const int16_t *frames, uint16_t frame_count);
uint16_t mi2s_write_buffer(const int16_t *frames, uint16_t frame_count);

#ifdef __cplusplus
}
#endif
