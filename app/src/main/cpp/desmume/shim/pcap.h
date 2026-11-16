// Stub libpcap for Android: infrastructure-mode wifi capture is unavailable.
#pragma once
#include <sys/types.h>
#include <sys/time.h>
#ifdef __cplusplus
extern "C" {
#endif
#define PCAP_ERRBUF_SIZE 256
typedef struct pcap pcap_t;
typedef struct pcap_if {
	struct pcap_if *next;
	char *name;
	char *description;
	void *addresses;
	unsigned int flags;
} pcap_if_t;
struct pcap_pkthdr {
	struct timeval ts;
	unsigned int caplen;
	unsigned int len;
};
typedef void (*pcap_handler)(u_char *, const struct pcap_pkthdr *, const u_char *);
int pcap_findalldevs(pcap_if_t **alldevs, char *errbuf);
void pcap_freealldevs(pcap_if_t *alldevs);
pcap_t *pcap_open_live(const char *source, int snaplen, int flags, int readtimeout, char *errbuf);
void pcap_close(pcap_t *dev);
int pcap_setnonblock(pcap_t *dev, int nonblock, char *errbuf);
int pcap_sendpacket(pcap_t *dev, const u_char *data, int len);
int pcap_dispatch(pcap_t *dev, int num, pcap_handler callback, u_char *userdata);
void pcap_breakloop(pcap_t *dev);
#ifdef __cplusplus
}
#endif
