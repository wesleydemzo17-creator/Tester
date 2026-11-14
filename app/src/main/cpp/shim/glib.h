// Minimal glib replacement for the DeSmuME core on Android.
// The core only uses these four functions (path.cpp).
#pragma once
#include <stdlib.h>
#ifdef __cplusplus
extern "C" {
#endif
const char *g_get_user_config_dir(void);
char *g_build_filename(const char *first, ...);
int g_mkdir_with_parents(const char *path, int mode);
void g_free(void *p);
#ifdef __cplusplus
}
#endif
