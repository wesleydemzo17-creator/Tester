# Simulador do PokeBank DS no PC (mesma logica do DS, cartucho = arquivo .sav).
CC ?= cc
CFLAGS ?= -O2 -g -Wall -Wextra -std=gnu11
SRC = ../source/app.c ../source/proto.c platform_host.c main_host.c

pokebank_host: $(SRC) ../source/*.h
	$(CC) $(CFLAGS) -o $@ $(SRC)

clean:
	rm -f pokebank_host
