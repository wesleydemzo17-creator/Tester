# PokeBank — banco de Pokémon entre Nintendo DS e Android

Projeto nº 9: um programa homebrew para o Nintendo DS lê o save do cartucho de Pokémon e conversa
por Wi‑Fi com um app Android. No celular você vê as 18 caixas do PC, **deposita** Pokémon no banco
do celular e **retira** de volta, para o mesmo cartucho ou para outro (o que na prática é uma troca
entre cartuchos).

Jogos suportados: **Diamond, Pearl, Platinum, HeartGold e SoulSilver** (4ª geração, save de 512 KB).
Funciona com **cartucho original, R4/flashcart, DSi/3DS (TWiLight)** e **emuladores no celular**.

```
 Nintendo DS (PokeBank DS)            Android (app PokeBank)
 ┌───────────────────────┐   UDP     ┌──────────────────────────┐
 │ 1. lê o save (flash)  │──"PKBANK?"│ responde com IP e porta  │
 │ 2. acha o celular     │◄──────────│                          │
 │ 3. envia o save 512KB │──TCP─────►│ backup + decodifica      │
 │                       │           │ caixas, banco (.pk4)     │
 │ 5. confirma (A, R+A)  │◄──────────│ 4. pedido de gravação    │
 │ 6. grava e confere    │──────────►│ 7. confirma no banco     │
 └───────────────────────┘           └──────────────────────────┘
```

## Pastas

| Pasta | O que tem |
|---|---|
| `ds/` | Programa do DS em C (libnds + calico + dswifi do devkitPro). `make` gera `pokebank.nds`. |
| `ds/host/` | Simulador no PC: a mesma lógica do DS, usando um arquivo `.sav` como cartucho. Serve para testar sem console. |
| `android/` | App Android (Kotlin + Jetpack Compose). Abra a pasta no Android Studio. |
| `android/app/src/main/java/br/pokebank/core/` | Núcleo: criptografia PK4, layout do save Gen 4, CRC, banco. Puro Kotlin, sem Android. |
| `android/app/src/main/java/br/pokebank/net/` | Protocolo e servidor (UDP de descoberta + TCP). Puro Kotlin. |
| `PROTOCOL.md` | Descrição byte a byte do protocolo, para você criar outros clientes. |
| `BATTLES.md` | Plano de trocas ao vivo e batalhas DS ↔ Android. |
| `WFC.md` | **Servidor WFC próprio no celular (root)**: batalhas e trocas reais do jogo entre DS e melonDS. |
| `android/.../wfc/` | O servidor: DNS, NAS, GPCM, GPSP (porte do AltWFC). |

## Como compilar

### DS

1. Instale o devkitPro com o grupo `nds-dev` (https://devkitpro.org/wiki/Getting_Started).
2. `cd ds && make` gera `pokebank.nds`.

### Android

1. Abra a pasta `android/` no Android Studio (Ladybug ou mais novo) e espere o Gradle sincronizar.
2. Rode no celular (Android 7.0 ou mais novo).
3. Testes do núcleo: `./gradlew test`.

### Simulador do DS no PC (opcional)

```
cd ds/host && make
PB_SERVER=192.168.0.10 PB_KEYS="A,RA" ./pokebank_host meu_save.sav
```

`PB_SERVER` é o IP do celular. `PB_KEYS` são os botões das telas de confirmação ("A" e depois "R+A").
A flash é simulada de verdade (apagar deixa 0xFF; gravar só muda bits de 1 para 0). Para simular um
chip com defeito, use `PB_FAIL_SECTOR=0x40000`.

## Como usar

### 1. Imagens dos Pokémon (uma vez só)

No app, toque em **Sprites da ROM** e escolha o arquivo `.nds` do seu jogo (Diamond, Pearl,
Platinum, HeartGold ou SoulSilver). O app extrai do próprio jogo as imagens dos 493 Pokémon (normal,
shiny e versões fêmea) e guarda no celular. Nenhuma imagem vem embutida no app.

### 2. Abrir um save — três jeitos

**a) R4 / flashcart (o mais comum)**
1. Jogue, **salve no jogo** e volte ao menu do R4. Alguns kernels só gravam o `.sav` no microSD nessa hora.
2. Abra o `pokebank.nds` pelo R4 e escolha **A = arquivo .sav no cartão**.
3. Ele procura os `.sav` de 512 KB no cartão; escolha o do Pokémon com as setas e **A**.

**b) DSi / 3DS (TWiLight Menu++ ou nds-bootstrap)**
- O mesmo menu **A**: ele procura no SD do console (`sd:/`, ex.: `sd:/roms/nds/saves/`) e no flashcart (`fat:/`).
- No DSi o Wi‑Fi aceita **WPA2** (configure em Configurações do DSi > Internet).

**c) Cartucho original no Slot‑1**
- Escolha **B = cartucho original**. No DS Lite com flashcart, troque o cartão quando ele pedir.

Nos três casos o DS acha o celular sozinho (ou aperte **Y** para digitar o IP) e envia o save.

**d) Emulador no celular (melonDS, DraStic)**
- No app, toque em **Abrir save** e escolha o `.sav` (melonDS) ou `.dsv` (DraStic).
- **Feche o jogo no emulador antes**, e abra de novo depois de salvar.

### 3. Usar

- **Caixas:** as 18 caixas, com as imagens. Toque para ver a ficha completa e depositar no banco.
- **Equipe:** os 6 da party, com HP e status calculados pelo jogo (somente leitura).
- **Banco:** os Pokémon guardados no celular (`.pk4`, abrem no PKHeX). Toque para retirar para o save aberto.
- **Pokédex:** os 493, com capturado/visto do seu save, quantos você tem, busca e filtros.
- **Treinador:** nome, ID, dinheiro, insígnias, tempo de jogo, Pokédex, e exportar a equipe para o Pokémon Showdown.
- Para gravar: **Gravar no DS** (confirme no DS com A e depois R+A) ou **Salvar no arquivo** (emulador).

Na ficha de cada Pokémon: natureza, habilidade, item, IVs, EVs, status, golpes e PP, treinador
original, local e data de captura, Pokébola, Pokérus, fitas, Poder Oculto e os dados base da espécie.

### Rede

O DS precisa de Wi‑Fi de 2,4 GHz. **DS / DS Lite** só conectam em redes **abertas ou WEP** (use o
roteador do celular sem senha). **DSi** aceita WPA2. A conexão é a salva nas configurações do console
("Nintendo WFC Settings" num jogo Pokémon, ou Configurações do DSi).

## Segurança do save

- Todo save recebido vira backup no celular antes de qualquer coisa.
- No modo arquivo, o DS também cria `<nome>.sav.bak` no cartão antes de gravar.
- Só o bloco das caixas é alterado (com o checksum recalculado, como o PKHeX faz). Rodapés de emulador
  e bytes extras do arquivo são preservados.
- O DS confere tudo lendo de volta; se algo falhar, regrava o original.
- Depósitos enviados ficam num diário até a confirmação; se o app fechar no meio, ele avisa.
- Itens e formas que não existem no jogo de destino são bloqueados.

## Limitações desta versão

- Equipe só para leitura; trocas passam pelo banco (troca ao vivo e batalhas: veja `BATTLES.md`).
- Cartuchos com infravermelho (HG/SS) no Slot‑1: acesso ao chip experimental (no R4 isso não existe: é arquivo).
- Textos do jogo (golpes, itens etc.) em inglês, como nos cartuchos ocidentais.

## Testado até aqui

- 25 testes de unidade (incluindo o servidor WFC: DNS, NAS, GPCM com dois DS simulados), entre eles: criptografia PK4 com arquivos reais, status conferidos com o
  exemplo da Bulbapedia (Garchomp), party, treinador e Pokédex, formatos de emulador, e
  decodificação de sprites validada contra o **nitrogfx** da pret nos dois modos de criptografia.
- Ponta a ponta (servidor real ↔ simulador do DS): gravação, cancelamento, chip com defeito e
  **modo arquivo** (`.sav` com rodapé preservado e `.bak` criado).
- O `.nds` e o APK são compilados no GitHub Actions a cada envio.
- **Ainda não testado em console nem celular de verdade.**

## Créditos e licença

- Caminhos e criptografia dos sprites conferidos nos projetos de decompilação da **pret** (pokeplatinum, pokeheartgold) e validados com o **nitrogfx**.
- Layouts do save, criptografia PK4, tabela de caracteres, nomes de espécies e curvas de experiência
  foram conferidos e extraídos do **PKHeX** (https://github.com/kwsch/PKHeX, GPLv3). Por isso este
  projeto deve ser distribuído sob a **GPLv3**.
- O servidor WFC (`wfc/`) é um porte do **AltWFC** (AGPLv3). Com ele, o projeto como um todo passa a seguir a **AGPLv3** (compatível com a GPLv3).
- Exemplos e bibliotecas do devkitPro: https://github.com/devkitPro/nds-examples.
- Pokémon é marca da Nintendo/Game Freak/The Pokémon Company. Projeto de fã, sem fins comerciais.
