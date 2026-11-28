# Desdual

Emulador de Nintendo DS e de Game Boy / Color / Advance para Android, num app só:

- **Nintendo DS**: [DeSmuME](https://github.com/TASEmulators/desmume) (GPL v2+)
- **Game Boy, Game Boy Color e Game Boy Advance**: [mGBA](https://github.com/mgba-emu/mgba) (MPL 2.0)

O código dos dois núcleos **não** está neste repositório: `scripts/fetch_desmume.sh` e
`scripts/fetch_mgba.sh` baixam commits fixados dos repositórios oficiais na hora do build, sem
modificar nada.

## Tela inicial (estilo XMB)

- Categorias na horizontal (Personalizar, Recentes, Nintendo DS, Game Boy Advance, Game Boy / Color)
  e os jogos na vertical. Deslize para os lados e para cima/baixo, ou use um controle.
- Cada jogo aparece com o ícone do cartucho (DS) ou a capa, o nome oficial e a região.
- Ao selecionar um jogo, a capa vira o fundo e uma **prévia em movimento** roda num quadro: são
  quadros gravados do seu próprio jogo (aparecem depois de alguns minutos jogando). Antes disso,
  a prévia usa a tela de título e uma captura baixadas.
- Toque de novo (ou A) para jogar; segure (ou X/Y) para as opções do jogo: **criar ícone na tela
  inicial**, **configurações próprias**, escolher capa, baixar imagens, apagar a prévia, renomear,
  apagar.
- **Personalizar**: tema de cor (9 cores ou mudando com a hora), fundo (onda animada, cor sólida ou
  imagem sua), capa como fundo, prévia em movimento, escurecimento, tamanho dos ícones, velocidade
  da animação, relógio, ordem dos jogos e download de capas.

### Configurações por jogo

Com "configurações próprias" ligadas, tudo que você muda no MENU dentro do jogo (vídeo, shaders,
layout das telas, controles, JIT, som...) vale só para aquele jogo. Desligado, o jogo usa as
configurações gerais. Os cheats já eram guardados por jogo.

### Ícones na tela inicial

"Criar ícone na tela inicial" coloca o jogo na tela do Android com a capa ou o ícone dele. Tocar
no ícone abre o jogo direto, já com as configurações dele, sem passar pela lista.

### Nomes e capas

Os nomes vêm do libretro-database (pelo código do cartucho ou CRC), gerados no build por
`scripts/fetch_gamedb.sh`. As capas, telas de título e capturas são baixadas do
[libretro-thumbnails](https://github.com/libretro-thumbnails) no celular, como no RetroArch
(dá para desligar em Personalizar ou escolher uma capa sua da galeria).

## Como baixar e instalar

Cada push na branch `main` gera o APK e publica em **Releases → latest** como
`desdual.apk`. Baixe esse arquivo direto no celular e toque nele para instalar
(precisa de Android 8.0 ou mais novo, 64 bits).

O mesmo APK também fica em Actions → artefato `desdual-apk`, mas ali ele vem dentro de um `.zip`
que precisa ser extraído antes.

No PC:

```bash
./scripts/fetch_desmume.sh && ./scripts/fetch_mgba.sh
./scripts/fetch_cheats.sh && ./scripts/fetch_cheats_gb.sh && ./scripts/fetch_gamedb.sh
gradle :app:assembleRelease     # Gradle 8.9+, JDK 17, Android SDK 35, NDK 27
```

## Parte do Nintendo DS (DeSmuME)

## Uso

- **MENU → Abrir ROM**: aceita `.nds` direto ou dentro de `.zip`, `.7z` ou `.rar` (RAR5 e arquivos com senha não são suportados). A ROM é copiada para a pasta do app.
- Tela de baixo = tela de toque do DS. Controles na tela; gamepads Bluetooth/USB e teclado também funcionam.
- Saves do jogo (`.dsv`) e 5 slots de savestate ficam em `Android/data/org.desmume.android.port/files/`.
- Botão Voltar abre o menu.

## Otimizações

| Item | O que faz |
| --- | --- |
| JIT ARM64 | Usa o recompilador dinâmico ARM do próprio DeSmuME (`utils/arm_jit`). Liga/desliga em Configurações. |
| Proteção do JIT | Se o app fechar sozinho logo após carregar um jogo com JIT, na próxima abertura o JIT é desligado automaticamente. |
| NEON | Os caminhos NEON do GPU 2D e da conversão de cores do núcleo são ativados em arm64. |
| 3D em threads | SoftRasterizer com até 4 threads. |
| Resolução interna | 1x a 4x (até 1024×768 por tela) para os gráficos 3D, pelo SoftRasterizer. Pesa bastante na CPU. |
| Shaders | Telas desenhadas com OpenGL ES 2.0: nítido, bilinear, sharp bilinear, grade LCD, scanlines e cores do DS. |
| Layout livre | Predefinições (empilhado, lado a lado, uma tela grande) e editor: arrastar para mover, pinça para redimensionar. Salvo por orientação. |
| 3D pela GPU | Opcional e experimental: usa o renderizador OpenGL ES 3 do próprio DeSmuME num contexto EGL fora da tela. Volta sozinho para a CPU se travar. |
| Monitor de desempenho | FPS, uso de CPU do app, clock de cada núcleo, uso e clock da GPU (quando o Android libera), RAM. Tamanho, opacidade e canto ajustáveis. |
| Tela esticada | No editor, o quadrado do canto estica largura e altura separadamente; há predefinições que preenchem a tela e um shader bicúbico para suavizar. |
| Frameskip | Automático (pula até 3 quadros quando atrasa) ou fixo de 0 a 4. |
| Vídeo | Quadro em RGB565 enviado como textura e escalado pela GPU. |
| Áudio | Mixagem assíncrona, uma fatia por quadro, direto para `AudioTrack`. |
| Compilação | `-O3`, somente `arm64-v8a`, páginas de 16 KB suportadas. |
| Avanço rápido | No menu, com o jogo aberto. |

## Controles na tela

MENU → **Controles na tela**: cinco estilos de botão, três formatos de direcional (cruz, disco,
quatro botões), opacidade, tamanho geral e um editor para mover e redimensionar cada controle
(arrastar; pinça ou − / +). As posições são salvas por orientação.

## Multiplayer local (experimental)

MENU → **Multiplayer local** liga a emulação sem fio do DeSmuME no modo ad-hoc: os quadros do
wireless do DS viajam como broadcast UDP (porta 7000) na rede Wi-Fi. Os aparelhos precisam estar
na mesma rede. Não usa Bluetooth. É experimental no próprio DeSmuME: muitos jogos não conectam.

## Cheats

MENU → **Cheats** (com um jogo aberto):

- **Buscar no banco**: escolha o jogo na lista (a busca já vem preenchida com o nome do arquivo) e marque os cheats.
- **Adicionar código manualmente**: códigos Action Replay, pares de números hexadecimais de 8 dígitos.
- **Ativar / desativar** e **Apagar**. Os cheats ficam salvos por jogo e voltam a valer ao reabrir.

O banco incluído vem do [libretro-database](https://github.com/libretro/libretro-database)
(pasta `cht/Nintendo - Nintendo DS`, licença CC BY-SA 4.0): cerca de 4.200 jogos. O build baixa
um commit fixado por `scripts/fetch_cheats.sh`. Cheats podem travar ou corromper o save de alguns
jogos; faça um savestate antes de ativar.

## Limitações conhecidas

- Somente aparelhos ARM de 64 bits, Android 8.0+.
- Esticar a tela só deforma a imagem 4:3 do DS; o jogo não passa a renderizar em 16:9 de verdade.
- Uso e clock da GPU dependem de arquivos do sistema que muitos aparelhos bloqueiam para apps.
- Sem microfone e sem Wi-Fi Connection (jogo online); o multiplayer local é experimental.
- Sem BIOS/firmware externos (usa a emulação HLE do núcleo).

## Estrutura

```
app/src/main/cpp/desmume/         núcleo do DS + ponte JNI (bridge.cpp) e substitutos de glib/libpcap
app/src/main/cpp/mgba/            núcleo do GBA/GB + ponte JNI (bridge.c)
app/src/main/java/org/emu/hub/    tela XMB, biblioteca, capas, prévias, configurações por jogo, atalhos
app/src/main/java/org/mgba/...    app do mGBA (controles, menus, cheats)
app/src/main/java/...             Native, EmuView (controles, layout, loop), ScreenRenderer + Shaders (OpenGL), MainActivity (menu)
tools/DumpShaders.java            usado no build para validar os shaders com glslangValidator
.github/workflows/build.yml       gera o APK no GitHub Actions
```

## Licença

A parte do DeSmuME é GPL v2 ou posterior (veja `LICENSE`); o mGBA é MPL 2.0, compatível com a GPL.
O banco de cheats vem do libretro-database (CC BY-SA 4.0).
