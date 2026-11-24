#include "proto.h"
#include "platform.h"

#include <sys/types.h>
#include <sys/socket.h>
#include <string.h>

uint32_t pb_crc32(uint32_t crc, const uint8_t* data, size_t len) {
    static uint32_t table[256];
    static bool ready = false;
    if (!ready) {
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) ? 0xEDB88320u ^ (c >> 1) : c >> 1;
            table[i] = c;
        }
        ready = true;
    }
    crc = ~crc;
    while (len--) crc = table[(crc ^ *data++) & 0xFF] ^ (crc >> 8);
    return ~crc;
}

int pb_rx_poll(PbRx* rx, int sock) {
    for (;;) {
        uint8_t* dst;
        uint32_t want;
        if (rx->got < 8) {
            dst = rx->header + rx->got;
            want = 8 - rx->got;
        } else {
            uint32_t done = rx->got - 8;
            if (done >= rx->length) break;
            dst = rx->payload + done;
            want = rx->length - done;
            if (want > 8192) want = 8192;
        }
        int n = (int)recv(sock, dst, want, 0);
        if (n == 0) return -1;            // o outro lado fechou
        if (n < 0) return plat_would_block() ? 0 : -1;
        rx->got += (uint32_t)n;
        if (rx->got == 8) {
            if (rx->header[0] != 'P' || rx->header[1] != 'B') return -1;
            rx->type = rx->header[2];
            rx->length = pb_rd32(rx->header + 4);
            if (rx->length > PB_MAX_PAYLOAD) return -1;
        }
    }
    rx->got = 0; // pronto para o proximo quadro
    return 1;
}

static bool send_all(int sock, const uint8_t* p, uint32_t len) {
    while (len) {
        uint32_t chunk = len > 4096 ? 4096 : len;
        int n = (int)send(sock, p, chunk, 0);
        if (n < 0) {
            if (plat_would_block()) { plat_frame(); continue; }
            return false;
        }
        p += n;
        len -= (uint32_t)n;
    }
    return true;
}

bool pb_send2(int sock, uint8_t type, const void* pre, uint32_t prelen, const void* body, uint32_t bodylen) {
    uint8_t h[8] = { 'P', 'B', type, 0 };
    pb_wr32(h + 4, prelen + bodylen);
    plat_set_nonblocking(sock, false);
    bool ok = send_all(sock, h, 8)
        && (prelen == 0 || send_all(sock, (const uint8_t*)pre, prelen))
        && (bodylen == 0 || send_all(sock, (const uint8_t*)body, bodylen));
    plat_set_nonblocking(sock, true);
    return ok;
}

bool pb_send(int sock, uint8_t type, const void* payload, uint32_t len) {
    return pb_send2(sock, type, payload, len, NULL, 0);
}
