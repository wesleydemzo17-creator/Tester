// PokeBank DS - logica principal (portatil).
//
// Fluxo:
//   1. conecta no Wi-Fi (configuracao WFC salva no console)
//   2. le o save do cartucho de Pokemon (DPPt/HGSS, flash de 512 KB)
//   3. acha o celular na rede (broadcast UDP) e envia o save
//   4. espera pedidos do celular; so grava no cartucho depois de o jogador confirmar,
//      e confere tudo lendo de volta.
#include "app.h"
#include "platform.h"
#include "proto.h"

#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/types.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define SECTOR 0x10000u
#define PAGE   256u

static uint8_t* g_save;    // copia do que esta no cartucho agora
static uint8_t* g_verify;  // buffer de leitura para conferencia
static SaveChipInfo g_chip;
static char g_code[5], g_title[13];
static PbRx g_rx;

static void wait_key(uint32_t mask) {
    while (plat_frame()) if (plat_keys_down() & mask) return;
}

static void ip_str(uint32_t ip, char* out) {
    const uint8_t* b = (const uint8_t*)&ip;
    sprintf(out, "%u.%u.%u.%u", b[0], b[1], b[2], b[3]);
}

// ---------------------------------------------------------------- cartucho

static bool read_save(uint8_t* dst) {
    for (uint32_t a = 0; a < g_chip.size; a += SECTOR) {
        plat_status("Lendo save... %3u%%", (unsigned)(a * 100 / g_chip.size));
        if (!plat_save_read(a, dst + a, SECTOR)) return false;
        plat_frame();
    }
    plat_status("Lendo save... 100%%");
    return true;
}

static bool open_cart(void) {
    if (!plat_cart_open(g_code, g_title)) return false;
    plat_log("Jogo: %s [%s]", g_title, g_code);
    if (!plat_save_detect(&g_chip)) {
        plat_log("Nao achei o chip de save.");
        return false;
    }
    plat_log("Chip %06lX, %lu KB%s", (unsigned long)g_chip.jedec, (unsigned long)(g_chip.size / 1024),
             g_chip.infrared ? ", IR" : "");
    if (g_chip.type != 3 || g_chip.size != 0x80000) {
        plat_log("So suporto saves flash de 512 KB");
        plat_log("(Pokemon D/P/Pt/HG/SS).");
        return false;
    }
    return true;
}

// ---------------------------------------------------------------- rede

static bool parse_discovery(const uint8_t* buf, int n, uint16_t* port, char* name) {
    if (n < 10 || memcmp(buf, "PKBANK!1", 8) != 0) return false;
    *port = pb_rd16(buf + 8);
    int len = n - 10;
    if (len > 32) len = 32;
    memcpy(name, buf + 10, (size_t)len);
    name[len] = 0;
    return true;
}

// Procura o celular. Retorna o IP (ordem de rede) ou 0. Y = digitar IP manualmente.
static uint32_t manual_ip(void);

static uint32_t discover(uint16_t* port, char* name) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    if (s < 0) return 0;
    int one = 1;
    setsockopt(s, SOL_SOCKET, SO_BROADCAST, &one, sizeof(one));
    struct sockaddr_in me = { 0 };
    me.sin_family = AF_INET;
    me.sin_port = htons(PB_LOCAL_UDP_PORT);
    me.sin_addr.s_addr = INADDR_ANY;
    bind(s, (struct sockaddr*)&me, sizeof(me));
    plat_set_nonblocking(s, true);

    struct sockaddr_in bc = { 0 };
    bc.sin_family = AF_INET;
    bc.sin_port = htons(PB_DISCOVERY_PORT);
    bc.sin_addr.s_addr = plat_discovery_addr();

    uint32_t found = 0;
    for (int frame = 0; plat_frame(); frame++) {
        if (frame % 60 == 0) {
            sendto(s, "PKBANK?1", 8, 0, (struct sockaddr*)&bc, sizeof(bc));
            plat_status("Procurando o celular... %ds\nY = digitar IP  START = sair", frame / 60);
        }
        uint8_t buf[64];
        struct sockaddr_in from;
        socklen_t fl = sizeof(from);
        int n = (int)recvfrom(s, buf, sizeof(buf), 0, (struct sockaddr*)&from, &fl);
        if (n > 0 && parse_discovery(buf, n, port, name)) { found = from.sin_addr.s_addr; break; }
        uint32_t k = plat_keys_down();
        if (k & K_START) break;
        if (k & K_Y) {
            uint32_t m = manual_ip();
            if (m) {
                found = m;
                *port = PB_TCP_PORT;
                strcpy(name, "manual");
                break;
            }
        }
    }
    plat_close_socket(s);
    return found;
}

static uint32_t manual_ip(void) {
    static uint8_t ip[4] = { 192, 168, 43, 1 }; // padrao de muitos hotspots Android
    int cur = 0;
    for (;;) {
        plat_status("IP do celular (setas, A=ok):\n\n   %s%3u%s.%s%3u%s.%s%3u%s.%s%3u%s",
                    cur == 0 ? "[" : " ", ip[0], cur == 0 ? "]" : " ",
                    cur == 1 ? "[" : " ", ip[1], cur == 1 ? "]" : " ",
                    cur == 2 ? "[" : " ", ip[2], cur == 2 ? "]" : " ",
                    cur == 3 ? "[" : " ", ip[3], cur == 3 ? "]" : " ");
        if (!plat_frame()) return 0;
        uint32_t k = plat_keys_down();
        if (k & K_LEFT && cur > 0) cur--;
        if (k & K_RIGHT && cur < 3) cur++;
        if (k & K_UP) ip[cur]++;
        if (k & K_DOWN) ip[cur]--;
        if (k & K_B) return 0;
        if (k & K_A) { uint32_t v; memcpy(&v, ip, 4); return v; }
    }
}

static int connect_to(uint32_t ip, uint16_t port) {
    int s = socket(AF_INET, SOCK_STREAM, 0);
    if (s < 0) return -1;
    struct sockaddr_in sa = { 0 };
    sa.sin_family = AF_INET;
    sa.sin_port = htons(port);
    sa.sin_addr.s_addr = ip;
    if (connect(s, (struct sockaddr*)&sa, sizeof(sa)) != 0) {
        plat_close_socket(s);
        return -1;
    }
    plat_set_nonblocking(s, true);
    return s;
}

static bool send_hello(int s) {
    uint8_t p[25] = { 0 };
    p[0] = PB_VERSION;
    p[1] = g_chip.type;
    pb_wr32(p + 4, g_chip.size);
    memcpy(p + 8, g_code, 4);
    memcpy(p + 12, g_title, 12);
    p[24] = g_chip.infrared;
    return pb_send(s, PB_HELLO, p, sizeof(p));
}

static bool send_save(int s) {
    uint8_t crc[4];
    pb_wr32(crc, pb_crc32(0, g_save, g_chip.size));
    plat_status("Enviando save (%lu KB)...", (unsigned long)(g_chip.size / 1024));
    bool ok = pb_send2(s, PB_SAVE_DATA, crc, 4, g_save, g_chip.size);
    plat_status(ok ? "Save enviado. Use o celular." : "Falha ao enviar o save.");
    if (ok) plat_log("Save enviado ao celular.");
    return ok;
}

// ---------------------------------------------------------------- gravacao

static bool sector_differs(const uint8_t* a, const uint8_t* b) { return memcmp(a, b, SECTOR) != 0; }

static bool page_blank(const uint8_t* p) {
    for (uint32_t i = 0; i < PAGE; i++) if (p[i] != 0xFF) return false;
    return true;
}

// Apaga, grava e confere um setor. Tenta duas vezes.
static bool write_sector(uint32_t addr, const uint8_t* data) {
    for (int attempt = 0; attempt < 2; attempt++) {
        if (!plat_save_erase_sector(addr)) continue;
        bool ok = true;
        for (uint32_t p = 0; p < SECTOR && ok; p += PAGE)
            if (!page_blank(data + p)) ok = plat_save_program(addr + p, data + p, PAGE);
        if (!ok) continue;
        if (plat_save_read(addr, g_verify, SECTOR) && memcmp(g_verify, data, SECTOR) == 0) return true;
    }
    return false;
}

// Grava 'next' no cartucho mexendo apenas nos setores que mudaram.
static uint8_t do_write(const uint8_t* next, uint16_t* sectors_out, uint32_t* crc_out) {
    uint32_t total = 0, done = 0;
    for (uint32_t a = 0; a < g_chip.size; a += SECTOR) if (sector_differs(next + a, g_save + a)) total++;
    *sectors_out = (uint16_t)total;
    plat_log("Setores a gravar: %lu", (unsigned long)total);

    for (uint32_t a = 0; a < g_chip.size; a += SECTOR) {
        if (!sector_differs(next + a, g_save + a)) continue;
        plat_status("GRAVANDO... nao desligue!\nNao tire o cartao!\n\nSetor %lu de %lu",
                    (unsigned long)(done + 1), (unsigned long)total);
        plat_frame();
        if (!write_sector(a, next + a)) {
            plat_log("ERRO no setor %05lX!", (unsigned long)a);
            // Devolve o conteudo original de todos os setores que ja mexemos (inclusive este).
            bool restored = true;
            for (uint32_t b = 0; b <= a; b += SECTOR)
                if (sector_differs(next + b, g_save + b)) restored &= write_sector(b, g_save + b);
            plat_log(restored ? "Save original restaurado." : "NAO consegui restaurar!");
            *crc_out = 0;
            return restored ? PB_WR_FAILED_RESTORED : PB_WR_FAILED_UNKNOWN;
        }
        done++;
    }
    // Conferencia final lendo o chip inteiro.
    plat_status("Conferindo...");
    for (uint32_t a = 0; a < g_chip.size; a += SECTOR) {
        if (!plat_save_read(a, g_verify, SECTOR) || memcmp(g_verify, next + a, SECTOR) != 0) {
            plat_log("Conferencia falhou em %05lX", (unsigned long)a);
            *crc_out = 0;
            return PB_WR_FAILED_UNKNOWN;
        }
        plat_frame();
    }
    memcpy(g_save, next, g_chip.size);
    *crc_out = pb_crc32(0, g_save, g_chip.size);
    return PB_WR_OK;
}

static bool handle_write_request(int s, uint8_t* p, uint32_t len) {
    uint8_t res[7] = { 0 };
    if (len < 8) return false;
    uint32_t crc = pb_rd32(p), sumlen = pb_rd32(p + 4);
    if (8 + sumlen + g_chip.size != len) {
        plat_log("Pedido de gravacao invalido.");
        res[0] = PB_WR_CANCELLED;
        return pb_send(s, PB_WRITE_RESULT, res, sizeof(res));
    }
    uint8_t* next = p + 8 + sumlen;
    if (pb_crc32(0, next, g_chip.size) != crc) {
        plat_log("CRC do pedido nao confere.");
        res[0] = PB_WR_CANCELLED;
        return pb_send(s, PB_WRITE_RESULT, res, sizeof(res));
    }

    // Mostra o resumo e pede confirmacao dupla.
    char summary[600];
    uint32_t n = sumlen < sizeof(summary) - 1 ? sumlen : sizeof(summary) - 1;
    memcpy(summary, p + 8, n);
    summary[n] = 0;
    plat_log("--- Pedido de gravacao ---");
    for (char* line = strtok(summary, "\n"); line; line = strtok(NULL, "\n")) plat_log("%s", line);

    bool confirmed = false;
    plat_status("Gravar no cartucho?\n\n A = sim     B = nao");
    while (plat_frame()) {
        uint32_t k = plat_keys_down();
        if (k & K_B) break;
        if (k & K_A) {
            plat_status("Tem certeza? Segure R e\naperte A para GRAVAR.\n\n B = cancelar");
            while (plat_frame()) {
                k = plat_keys_down();
                if (k & K_B) break;
                if ((k & K_A) && (plat_keys_held() & K_R)) { confirmed = true; break; }
            }
            break;
        }
    }
    if (!confirmed) {
        plat_log("Gravacao cancelada.");
        plat_status("Cancelado. X = reenviar save");
        res[0] = PB_WR_CANCELLED;
        return pb_send(s, PB_WRITE_RESULT, res, sizeof(res));
    }

    uint16_t sectors = 0;
    uint32_t outcrc = 0;
    res[0] = do_write(next, &sectors, &outcrc);
    pb_wr32(res + 1, outcrc);
    pb_wr16(res + 5, sectors);
    if (res[0] == PB_WR_OK) {
        plat_log("Gravado e conferido!");
        plat_status("Pronto! Pode voltar ao jogo.\nX = reenviar  START = sair");
    } else {
        plat_status("FALHOU (codigo %u).\nVeja o celular.", res[0]);
    }
    return pb_send(s, PB_WRITE_RESULT, res, sizeof(res));
}

// ---------------------------------------------------------------- principal

int app_main(void) {
    plat_init();
    plat_log("PokeBank DS v%d", PB_VERSION);

    g_save = (uint8_t*)malloc(0x80000);
    g_verify = (uint8_t*)malloc(SECTOR);
    g_rx.payload = (uint8_t*)malloc(PB_MAX_PAYLOAD);
    if (!g_save || !g_verify || !g_rx.payload) {
        plat_log("Sem memoria!");
        wait_key(K_START);
        return 1;
    }

    if (!open_cart()) { plat_status("START = sair"); wait_key(K_START); return 1; }
    if (!read_save(g_save)) { plat_log("Erro lendo o save."); wait_key(K_START); return 1; }
    plat_log("Save lido (CRC %08lX)", (unsigned long)pb_crc32(0, g_save, g_chip.size));

    if (!plat_wifi_connect()) { plat_status("Wi-Fi falhou. START = sair"); wait_key(K_START); return 1; }
    char ipbuf[16];
    ip_str(plat_local_ip(), ipbuf);
    plat_log("Meu IP: %s", ipbuf);

    for (;;) {
        uint16_t port = PB_TCP_PORT;
        char name[33] = "";
        uint32_t server = discover(&port, name);
        if (!server) return 0;
        ip_str(server, ipbuf);
        plat_log("Celular: %s (%s:%u)", name, ipbuf, port);

        int s = connect_to(server, port);
        if (s < 0) { plat_log("Nao consegui conectar."); continue; }
        if (!send_hello(s) || !send_save(s)) { plat_close_socket(s); continue; }

        bool quit = false;
        while (!quit && plat_frame()) {
            int r = pb_rx_poll(&g_rx, s);
            if (r < 0) { plat_log("Conexao perdida."); break; }
            if (r > 0) {
                uint8_t* p = g_rx.payload;
                uint32_t len = g_rx.length;
                switch (g_rx.type) {
                case PB_HELLO_ACK:
                    if (len > 1) plat_log("Conectado a: %.*s", (int)(len - 1), (const char*)p + 1);
                    break;
                case PB_MESSAGE:
                    plat_log("> %.*s", (int)len, (const char*)p);
                    break;
                case PB_REQUEST_SAVE:
                    plat_log("Celular pediu o save de novo.");
                    if (read_save(g_save)) send_save(s);
                    break;
                case PB_WRITE_REQUEST:
                    if (!handle_write_request(s, p, len)) quit = true;
                    break;
                case PB_BYE:
                    plat_log("Celular encerrou.");
                    quit = true;
                    break;
                default:
                    break;
                }
            }
            uint32_t k = plat_keys_down();
            if (k & K_X) {
                plat_log("Relendo o cartucho...");
                if (read_save(g_save)) send_save(s);
            }
            if (k & K_START) {
                pb_send(s, PB_BYE, NULL, 0);
                plat_close_socket(s);
                return 0;
            }
        }
        plat_close_socket(s);
        if (quit) return 0;
    }
}
