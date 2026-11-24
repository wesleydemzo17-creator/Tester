// PokeBank DS - plataforma de TESTE no PC (Linux/macOS).
// Roda exatamente a mesma logica do DS (app.c/proto.c) contra o servidor do Android,
// usando um arquivo .sav como "cartucho". Simula a flash de verdade: apagar deixa 0xFF
// e programar so consegue mudar bits de 1 para 0.
//
// Variaveis de ambiente:
//   PB_SERVER=127.0.0.1    para onde mandar a descoberta (padrao: 255.255.255.255)
//   PB_KEYS="A,RA"         botoes entregues nas telas de confirmacao (as que tem '?').
//                          "A" = aperta A, "RA" = segura R e aperta A, "B" = cancela.
//   PB_EXIT_AFTER=1        encerra depois de N resultados de gravacao
//   PB_FAIL_SECTOR=0x40000 simula defeito: programar neste setor falha
#include "../source/platform.h"

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <unistd.h>

static const char* s_path;
static uint8_t s_flash[0x80000];
static uint32_t s_down, s_held;
static int s_prompt, s_results, s_exit_after = -1;
static const char* s_keys;
static long s_fail_sector = -1;
static int s_failed_once;

void host_set_cart(const char* path) { s_path = path; }

static void flush_file(void) {
    FILE* f = fopen(s_path, "wb");
    if (f) { fwrite(s_flash, 1, sizeof(s_flash), f); fclose(f); }
}

void plat_init(void) {
    FILE* f = fopen(s_path, "rb");
    if (!f || fread(s_flash, 1, sizeof(s_flash), f) != sizeof(s_flash)) { fprintf(stderr, "nao li %s\n", s_path); exit(2); }
    fclose(f);
    s_keys = getenv("PB_KEYS");
    if (getenv("PB_EXIT_AFTER")) s_exit_after = atoi(getenv("PB_EXIT_AFTER"));
    if (getenv("PB_FAIL_SECTOR")) s_fail_sector = strtol(getenv("PB_FAIL_SECTOR"), NULL, 0);
}

bool plat_frame(void) {
    if (s_exit_after >= 0 && s_results >= s_exit_after) return false;
    usleep(2000);
    s_down = s_held = 0;
    if (s_prompt && s_keys && *s_keys) {
        if (s_keys[0] == 'R' && s_keys[1] == 'A') { s_down = K_A; s_held = K_R | K_A; s_keys += 2; }
        else if (s_keys[0] == 'A') { s_down = K_A; s_keys++; }
        else if (s_keys[0] == 'B') { s_down = K_B; s_keys++; }
        if (*s_keys == ',') s_keys++;
        s_prompt = 0;
    }
    return true;
}

uint32_t plat_keys_down(void) { return s_down; }
uint32_t plat_keys_held(void) { return s_held; }

void plat_log(const char* fmt, ...) {
    va_list ap; va_start(ap, fmt);
    printf("[DS] "); vprintf(fmt, ap); printf("\n");
    va_end(ap);
    fflush(stdout);
}

void plat_status(const char* fmt, ...) {
    char buf[512];
    va_list ap; va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (strchr(buf, '?')) s_prompt = 1;
    if (strstr(buf, "Pronto!") || strstr(buf, "Cancelado") || strstr(buf, "FALHOU")) s_results++;
    static char last[512];
    if (strcmp(last, buf) != 0 && !strstr(buf, "%")) {
        for (char* c = buf; *c; c++) if (*c == '\n') *c = '|';
        printf("[tela de baixo] %s\n", buf);
        fflush(stdout);
    }
    strcpy(last, buf);
}

void plat_status_clear(void) {}

bool plat_wifi_connect(void) { return true; }
uint32_t plat_local_ip(void) { return htonl(INADDR_LOOPBACK); }
uint32_t plat_discovery_addr(void) {
    const char* s = getenv("PB_SERVER");
    return s ? inet_addr(s) : htonl(INADDR_BROADCAST);
}

bool plat_cart_open(char game_code[5], char title[13]) {
    strcpy(game_code, "CPUP");
    strcpy(title, "POKEMON PL");
    return true;
}

bool plat_save_detect(SaveChipInfo* info) {
    memset(info, 0, sizeof(*info));
    info->size = sizeof(s_flash);
    info->type = 3;
    info->jedec = 0x204013;
    return true;
}

bool plat_save_read(uint32_t addr, uint8_t* buf, uint32_t len) {
    memcpy(buf, s_flash + addr, len);
    return true;
}

bool plat_save_erase_sector(uint32_t addr) {
    if (s_fail_sector >= 0 && (long)(addr & ~0xFFFFu) == s_fail_sector) s_failed_once++;
    memset(s_flash + (addr & ~0xFFFFu), 0xFF, 0x10000);
    flush_file();
    return true;
}

bool plat_save_program(uint32_t addr, const uint8_t* buf, uint32_t len) {
    if (s_fail_sector >= 0 && (long)(addr & ~0xFFFFu) == s_fail_sector && s_failed_once <= 2) {
        // simula chip com defeito nas duas primeiras tentativas de gravar este setor
        for (uint32_t i = 0; i < len; i++) s_flash[addr + i] &= (uint8_t)(buf[i] & 0x7F);
        flush_file();
        return true;
    }
    for (uint32_t i = 0; i < len; i++) s_flash[addr + i] &= buf[i]; // flash: so 1 -> 0
    flush_file();
    return true;
}

int plat_close_socket(int s) { return close(s); }

void plat_set_nonblocking(int s, bool on) {
    int fl = fcntl(s, F_GETFL, 0);
    fcntl(s, F_SETFL, on ? (fl | O_NONBLOCK) : (fl & ~O_NONBLOCK));
}

bool plat_would_block(void) { return errno == EWOULDBLOCK || errno == EAGAIN || errno == EINPROGRESS; }
