# DeSmuME para Android (port de teste)

Port do núcleo oficial do DeSmuME ([TASEmulators/desmume](https://github.com/TASEmulators/desmume), GPL v2+) para Android, com uma ponte JNI e um app enxuto em Java.

O código do DeSmuME **não** está neste repositório: `scripts/fetch_desmume.sh` baixa o commit fixado `40eafedd` do repositório oficial na hora do build. Nenhum arquivo do núcleo é modificado.

## Como gerar o APK (pelo celular, sem PC)

1. Crie um repositório novo no GitHub e envie todo o conteúdo desta pasta (incluindo `.github/`).
2. Abra a aba **Actions** → **Build APK** → **Run workflow** (ou faça um push na branch `main`).
3. Quando terminar, abra a execução e baixe o artefato **desmume-android-apk**.
4. Descompacte e instale o `app-release.apk` (permita "instalar apps desconhecidos").

## Como gerar no PC

```bash
./scripts/fetch_desmume.sh
gradle :app:assembleRelease     # Gradle 8.9+, JDK 17, Android SDK 35, NDK 27
```

O APK sai em `app/build/outputs/apk/release/`. Ele é assinado com a chave de debug, só para testes.

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
app/src/main/cpp/CMakeLists.txt   build do núcleo + ponte (também compila em Linux para testes)
app/src/main/cpp/bridge.cpp       ponte JNI: vídeo, áudio, entrada, saves
app/src/main/cpp/shim/            substitutos mínimos de glib e libpcap
app/src/main/java/...             Native, EmuView (controles, layout, loop), ScreenRenderer + Shaders (OpenGL), MainActivity (menu)
tools/DumpShaders.java            usado no build para validar os shaders com glslangValidator
.github/workflows/build.yml       gera o APK no GitHub Actions
```

## Licença

GPL v2 ou posterior, como o DeSmuME. Veja `LICENSE`.
