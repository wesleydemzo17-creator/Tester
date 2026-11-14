// Implementations for the glib/pcap shims.
#include <glib.h>
#include <pcap.h>
#include <stdarg.h>
#include <string.h>
#include <stdio.h>
#include <errno.h>
#include <sys/stat.h>
#include <string>

std::string g_desmumeBaseDir = ".";

extern "C" {

const char *g_get_user_config_dir(void) { return g_desmumeBaseDir.c_str(); }

char *g_build_filename(const char *first, ...)
{
	std::string out = first ? first : "";
	va_list ap;
	va_start(ap, first);
	for (const char *s = va_arg(ap, const char *); s != NULL; s = va_arg(ap, const char *))
	{
		if (!out.empty() && out[out.size() - 1] != '/') out += '/';
		out += s;
	}
	va_end(ap);
	return strdup(out.c_str());
}

int g_mkdir_with_parents(const char *path, int mode)
{
	std::string p = path;
	for (size_t i = 1; i <= p.size(); i++)
	{
		if (i == p.size() || p[i] == '/')
		{
			std::string sub = p.substr(0, i);
			if (mkdir(sub.c_str(), (mode_t)mode) != 0 && errno != EEXIST) return -1;
		}
	}
	return 0;
}

void g_free(void *p) { free(p); }

int pcap_findalldevs(pcap_if_t **alldevs, char *errbuf)
{
	if (alldevs) *alldevs = NULL;
	if (errbuf) snprintf(errbuf, PCAP_ERRBUF_SIZE, "libpcap is not available on Android");
	return -1;
}
void pcap_freealldevs(pcap_if_t *) {}
pcap_t *pcap_open_live(const char *, int, int, int, char *errbuf)
{
	if (errbuf) snprintf(errbuf, PCAP_ERRBUF_SIZE, "libpcap is not available on Android");
	return NULL;
}
void pcap_close(pcap_t *) {}
int pcap_setnonblock(pcap_t *, int, char *) { return -1; }
int pcap_sendpacket(pcap_t *, const u_char *, int) { return -1; }
int pcap_dispatch(pcap_t *, int, pcap_handler, u_char *) { return -1; }
void pcap_breakloop(pcap_t *) {}

}
