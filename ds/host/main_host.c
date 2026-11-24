// PokeBank DS - entrada do simulador no PC: ./pokebank_host arquivo.sav
#include "../source/app.h"
#include <stdio.h>
void host_set_cart(const char* path);
int main(int argc, char** argv) {
    if (argc < 2) { fprintf(stderr, "uso: %s save.sav\n", argv[0]); return 2; }
    host_set_cart(argv[1]);
    return app_main();
}
