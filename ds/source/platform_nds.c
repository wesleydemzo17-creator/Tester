// PokeBank DS - implementacao da plataforma para o Nintendo DS (libnds + calico + dswifi).
#include "platform.h"

#include <nds.h>
#include <dswifi9.h>
#include <fat.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <string.h>

static PrintConsole s_top, s_bottom;
static uint32_t s_down, s_held;

void plat_init(void) {
    videoSetMode(MODE_0_2D);
    videoSetModeSub(MODE_0_2D);
    vramSetBankA(VRAM_A_MAIN_BG);
    vramSetBankC(VRAM_C_SUB_BG);
    consoleInit(&s_top, 3, BgType_Text4bpp, BgSize_T_256x256, 31, 0, true, true);
    consoleInit(&s_bottom, 3, BgType_Text4bpp, BgSize_T_256x256, 31, 0, false, true);
}

static uint32_t map_keys(uint32_t k) {
    uint32_t r = 0;
    if (k & KEY_A) r |= K_A;
    if (k & KEY_B) r |= K_B;
    if (k & KEY_X) r |= K_X;
    if (k & KEY_Y) r |= K_Y;
    if (k & KEY_START) r |= K_START;
    if (k & KEY_UP) r |= K_UP;
    if (k & KEY_DOWN) r |= K_DOWN;
    if (k & KEY_LEFT) r |= K_LEFT;
    if (k & KEY_RIGHT) r |= K_RIGHT;
    if (k & KEY_R) r |= K_R;
    return r;
}

bool plat_frame(void) {
    if (!pmMainLoop()) return false;
    swiWaitForVBlank();
    scanKeys();
    s_down = map_keys(keysDown());
    s_held = map_keys(keysHeld());
    return true;
}

uint32_t plat_keys_down(void) { return s_down; }
uint32_t plat_keys_held(void) { return s_held; }

void plat_log(const char* fmt, ...) {
    consoleSelect(&s_top);
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
    printf("\n");
}

void plat_status(const char* fmt, ...) {
    consoleSelect(&s_bottom);
    consoleClear();
    printf("\x1b[1;1H== PokeBank DS ==\n\n");
    va_list ap;
    va_start(ap, fmt);
    vprintf(fmt, ap);
    va_end(ap);
}

void plat_status_clear(void) {
    consoleSelect(&s_bottom);
    consoleClear();
}

// ---------------------------------------------------------------- Wi-Fi

bool plat_wifi_connect(void) {
    static const char* const names[] = { "Desconectado", "Procurando rede", "Associando", "Pegando IP (DHCP)", "Conectado" };
    plat_status("Iniciando Wi-Fi...");
    if (!Wifi_InitDefault(false)) return false;
    Wifi_AutoConnect(); // usa as redes salvas na configuracao WFC do console
    int last = -1;
    while (plat_frame()) {
        int st = Wifi_AssocStatus();
        if (st != last) {
            plat_status("Wi-Fi: %s\n\nSTART = cancelar\n\nDica: configure a rede em\n\"Nintendo WFC Settings\"\n(menu do jogo Pokemon) ou\nnas Config. do DSi.",
                        (st >= 0 && st <= 4) ? names[st] : "?");
            last = st;
        }
        if (st == ASSOCSTATUS_ASSOCIATED) return true;
        if (st == ASSOCSTATUS_CANNOTCONNECT) return false; // mesmo criterio do Wifi_InitDefault(true)
        if (plat_keys_down() & K_START) return false;
    }
    return false;
}

uint32_t plat_local_ip(void) { return (uint32_t)Wifi_GetIP(); }

uint32_t plat_discovery_addr(void) { return INADDR_BROADCAST; }

int plat_close_socket(int s) { return closesocket(s); }

void plat_set_nonblocking(int s, bool on) {
    unsigned long v = on ? 1 : 0;
    ioctl(s, FIONBIO, &v);
}

bool plat_would_block(void) { return errno == EWOULDBLOCK || errno == EAGAIN || errno == EINPROGRESS; }

// ---------------------------------------------------------------- cartao de memoria

static int s_fat = -1;

bool plat_fs_init(void) {
    // Monta "fat:/" (flashcart/R4 via DLDI) e, no modo DSi, "sd:/" (SD interno).
    if (s_fat < 0) s_fat = fatInitDefault() ? 1 : 0;
    return s_fat == 1;
}

int plat_storage_roots(const char** roots, int max) {
    int n = 0;
    if (isDSiMode() && n < max) roots[n++] = "sd:/";
    if (n < max) roots[n++] = "fat:/";
    return n;
}

// ---------------------------------------------------------------- cartucho

static bool header_ok(const uint8_t* h) {
    for (int i = 0; i < 4; i++) {
        uint8_t c = h[0x0C + i];
        if (c < 0x20 || c > 0x7E) return false;
    }
    return true;
}

bool plat_cart_open(char game_code[5], char title[13]) {
    static u8 h1[512], h2[512];
    if (!ntrcardOpen()) {
        plat_log("Slot-1 indisponivel.");
        return false;
    }
    for (;;) {
        cardReadHeader(h1);
        cardReadHeader(h2);
        if (memcmp(h1, h2, 32) == 0 && header_ok(h1)) break;
        plat_status("Insira o cartucho Pokemon\n(DPPt/HGSS) no Slot-1.\n\nSe ele ja esta la, tire e\ncoloque de novo.\n\nA = pronto   START = sair");
        for (;;) {
            if (!plat_frame()) return false;
            uint32_t k = plat_keys_down();
            if (k & K_START) return false;
            if (k & K_A) break;
        }
    }
    memcpy(title, h1, 12);
    title[12] = 0;
    for (int i = 11; i >= 0 && (title[i] == ' ' || title[i] == 0); i--) title[i] = 0;
    memcpy(game_code, h1 + 0x0C, 4);
    game_code[4] = 0;
    return true;
}

// ---------------------------------------------------------------- chip de save (SPI)
//
// Driver proprio (em vez de cardReadEeprom/cardWriteEeprom) para:
//  - suportar cartoes com infravermelho (HGSS), que exigem um byte 0x00 antes de cada comando;
//  - gravar flash do jeito certo: apagar o setor (0xD8) e depois programar paginas (0x02).
// Comandos SPI flash padrao: 0x03 READ, 0x05 RDSR, 0x06 WREN, 0x02 PP, 0xD8 SE, 0x9F RDID.

#define SPI_SELECT (CARD_ENABLE | CARD_SPI_ENABLE | CARD_SPI_HOLD)
#define SPI_DESELECT (CARD_SPI_HOLD)

static bool s_ir = false;

static inline void spi_wait(void) { while (REG_AUXSPICNT & CARD_SPI_BUSY); }
static inline u8 spi_xfer(u8 v) { REG_AUXSPIDATA = v; spi_wait(); return REG_AUXSPIDATA; }

static void ir_passthrough(void) {
    // EXPERIMENTAL: mesmo procedimento usado por ferramentas de backup de save para cartoes IR.
    REG_AUXSPICNT = SPI_SELECT;
    swiDelay(12000);
    spi_xfer(0x00);
    swiDelay(12000);
    REG_AUXSPICNT = SPI_DESELECT;
}

static void spi_begin(void) {
    if (s_ir) ir_passthrough();
    REG_AUXSPICNT = SPI_SELECT;
}

static void spi_end(void) {
    spi_wait();
    REG_AUXSPICNT = SPI_DESELECT;
}

static void spi_addr(u32 a) {
    spi_xfer((u8)(a >> 16));
    spi_xfer((u8)(a >> 8));
    spi_xfer((u8)a);
}

static u32 read_jedec(void) {
    spi_begin();
    spi_xfer(0x9F);
    u32 id = 0;
    for (int i = 0; i < 3; i++) id = (id << 8) | spi_xfer(0);
    spi_end();
    return id;
}

static bool wait_ready(void) {
    for (u32 tries = 0; tries < 3000000; tries++) { // apagar um setor leva ate ~3 s
        spi_begin();
        spi_xfer(0x05);
        u8 sr = spi_xfer(0);
        spi_end();
        if (!(sr & 0x01)) return true;
    }
    return false;
}

static void write_enable(void) {
    spi_begin();
    spi_xfer(0x06);
    spi_end();
}

static u32 size_from_jedec(u32 id) {
    u8 maker = (u8)(id >> 16);
    u16 dev = (u16)id;
    if (maker == 0x62 && dev == 0x1100) return 512 * 1024;   // Sanyo
    if (maker == 0xC2 && dev == 0x2211) return 128 * 1024;   // Macronix MX25L1021E
    u8 cap = (u8)id;                                         // padrao JEDEC: 2^cap bytes
    if (cap >= 0x10 && cap <= 0x17) return 1u << cap;
    return 0;
}

bool plat_save_detect(SaveChipInfo* info) {
    memset(info, 0, sizeof(*info));
    s_ir = false;
    u32 id = read_jedec();
    if (id == 0xFFFFFF || id == 0) {
        s_ir = true; // talvez seja um cartao com infravermelho (HeartGold/SoulSilver)
        id = read_jedec();
        if (id == 0xFFFFFF || id == 0) { s_ir = false; return false; }
    }
    info->jedec = id;
    info->infrared = s_ir;
    info->type = 3;
    info->size = size_from_jedec(id);
    if (!info->size) {
        plat_log("Chip desconhecido; assumindo 512 KB");
        info->size = 512 * 1024;
    }
    return true;
}

bool plat_save_read(uint32_t addr, uint8_t* buf, uint32_t len) {
    spi_begin();
    spi_xfer(0x03);
    spi_addr(addr);
    for (uint32_t i = 0; i < len; i++) buf[i] = spi_xfer(0);
    spi_end();
    return true;
}

bool plat_save_erase_sector(uint32_t addr) {
    if (!wait_ready()) return false;
    write_enable();
    spi_begin();
    spi_xfer(0xD8);
    spi_addr(addr & ~0xFFFFu);
    spi_end();
    return wait_ready();
}

bool plat_save_program(uint32_t addr, const uint8_t* buf, uint32_t len) {
    if (len == 0 || len > 256 || ((addr & 0xFF) + len) > 256) return false; // nao pode cruzar pagina
    if (!wait_ready()) return false;
    write_enable();
    spi_begin();
    spi_xfer(0x02);
    spi_addr(addr);
    for (uint32_t i = 0; i < len; i++) spi_xfer(buf[i]);
    spi_end();
    return wait_ready();
}
