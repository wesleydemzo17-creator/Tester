// PokeBank DS - camada de plataforma.
// A logica (app.c, proto.c) e portatil; platform_nds.c implementa isto no Nintendo DS
// e host/platform_host.c implementa no PC (para testar contra o servidor do Android).
#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#define K_A     (1u << 0)
#define K_B     (1u << 1)
#define K_X     (1u << 2)
#define K_Y     (1u << 3)
#define K_START (1u << 4)
#define K_UP    (1u << 5)
#define K_DOWN  (1u << 6)
#define K_LEFT  (1u << 7)
#define K_RIGHT (1u << 8)
#define K_R     (1u << 9)

void plat_init(void);
// Espera o proximo quadro (1/60 s) e le os botoes. Retorna false se o sistema pediu para sair.
bool plat_frame(void);
uint32_t plat_keys_down(void);   // botoes apertados neste quadro
uint32_t plat_keys_held(void);   // botoes segurados

// Tela de cima: registro de mensagens. Tela de baixo: status/menu.
void plat_log(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void plat_status(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
void plat_status_clear(void);

// Wi-Fi: conecta usando a configuracao salva (WFC). Retorna false se falhar/cancelar.
bool plat_wifi_connect(void);
uint32_t plat_local_ip(void); // ordem de rede (como sin_addr.s_addr)

// Cartucho (Slot-1). Pode pedir para o usuario trocar o cartao.
bool plat_cart_open(char game_code[5], char title[13]);

typedef struct {
    uint32_t size;      // bytes
    uint8_t  type;      // 3 = flash (o unico suportado para gravar)
    uint8_t  infrared;  // 1 = cartao com infravermelho (HGSS)
    uint32_t jedec;     // ID do chip
} SaveChipInfo;

bool plat_save_detect(SaveChipInfo* info);
bool plat_save_read(uint32_t addr, uint8_t* buf, uint32_t len);
bool plat_save_erase_sector(uint32_t addr);                   // setor de 64 KB
bool plat_save_program(uint32_t addr, const uint8_t* buf, uint32_t len); // ate 256 bytes, alinhado

// Rede (sockets BSD; no DS o fechamento e closesocket()).
// Endereco para a descoberta UDP (no DS: 255.255.255.255). Ordem de rede.
uint32_t plat_discovery_addr(void);
int  plat_close_socket(int s);
void plat_set_nonblocking(int s, bool on);
bool plat_would_block(void);
