// PokeBank DS - protocolo com o app Android (veja PROTOCOL.md). Tudo little-endian.
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define PB_VERSION        1
#define PB_DISCOVERY_PORT 47100
#define PB_TCP_PORT       47101
#define PB_LOCAL_UDP_PORT 47102
#define PB_MAX_PAYLOAD    (0x80000 + 4096)

enum {
    PB_HELLO = 0x01, PB_HELLO_ACK = 0x02, PB_SAVE_DATA = 0x03, PB_MESSAGE = 0x04,
    PB_WRITE_REQUEST = 0x05, PB_WRITE_RESULT = 0x06, PB_REQUEST_SAVE = 0x07, PB_BYE = 0x09,
};

enum {
    PB_WR_OK = 0, PB_WR_CANCELLED = 1, PB_WR_FAILED_RESTORED = 2, PB_WR_FAILED_UNKNOWN = 3,
};

uint32_t pb_crc32(uint32_t crc, const uint8_t* data, size_t len); // comece com crc = 0
static inline uint16_t pb_rd16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
static inline uint32_t pb_rd32(const uint8_t* p) { return p[0] | (p[1] << 8) | (p[2] << 16) | ((uint32_t)p[3] << 24); }
static inline void pb_wr16(uint8_t* p, uint16_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); }
static inline void pb_wr32(uint8_t* p, uint32_t v) { p[0] = (uint8_t)v; p[1] = (uint8_t)(v >> 8); p[2] = (uint8_t)(v >> 16); p[3] = (uint8_t)(v >> 24); }

// Receptor nao-bloqueante: chame pb_rx_poll a cada quadro.
typedef struct {
    uint8_t  header[8];
    uint32_t got;      // bytes recebidos do quadro atual (cabecalho + payload)
    uint32_t length;   // tamanho do payload
    uint8_t  type;
    uint8_t* payload;  // buffer de PB_MAX_PAYLOAD bytes
} PbRx;

// 1 = quadro completo em rx->type/payload/length; 0 = ainda nao; -1 = conexao caiu/erro.
int pb_rx_poll(PbRx* rx, int sock);

// Envia um quadro inteiro (bloqueante). Retorna false em erro.
bool pb_send(int sock, uint8_t type, const void* payload, uint32_t len);
// Envia um quadro cujo payload e "prefixo + corpo" sem copiar o corpo.
bool pb_send2(int sock, uint8_t type, const void* pre, uint32_t prelen, const void* body, uint32_t bodylen);
