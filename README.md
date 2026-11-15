# mGBA para Android (port de teste)

Port do núcleo oficial do [mGBA](https://github.com/mgba-emu/mgba) (MPL 2.0) para Android: Game Boy
Advance, Game Boy e Game Boy Color. O app tem uma ponte JNI (`bridge.c`) e uma interface em Java.

O código do mGBA **não** está neste repositório: `scripts/fetch_mgba.sh` baixa o commit fixado
`d58ea8a8` do repositório oficial na hora do build, e nenhum arquivo dele é modificado. As interfaces
de PC (Qt e SDL) não são usadas.

## Como gerar o APK

Cada push nesta branch (`mgba-android`) roda o workflow **Build mGBA APK** no GitHub Actions.
Abra a execução, baixe o artefato **mgba-android-apk**, descompacte e instale o APK.

No PC: `./scripts/fetch_mgba.sh && ./scripts/fetch_cheats.sh && gradle :app:assembleRelease`
(Gradle 8.9+, JDK 17, Android SDK 35, NDK 27).

## O que veio do mGBA de PC

| Função | Onde fica |
| --- | --- |
| GBA, GB e GBC; ROMs soltas ou dentro de `.zip` / `.7z` | MENU → Abrir ROM |
| Saves de bateria automáticos (`.sav`) | pasta `saves` |
| 9 slots de savestate, com miniatura e save embutidos | MENU → Salvar / Carregar estado |
| Rebobinar (10, 30 ou 60 s) | Emulação → Rebobinar; segure ◀◀ |
| Avanço rápido (2x, 3x, 4x, 8x, máximo) | botão ▶▶ ou MENU |
| Cheats: GameShark, Action Replay, CodeBreaker, Game Genie | MENU → Cheats |
| Resolução interna 2x a 6x (renderizador OpenGL do mGBA, só GBA) | Vídeo → Resolução interna |
| Ligar/desligar camadas de vídeo e canais de áudio | Vídeo / Áudio |
| Captura de tela em PNG | MENU → Captura de tela |
| BIOS do GBA opcional, com ou sem abertura | Emulação → BIOS |
| Patches `.ips`, `.ups`, `.bps` | Emulação → Aplicar patch |
| Frameskip, otimização de idle loops, direções opostas | Emulação |
| Modelo de Game Boy, bordas do Super Game Boy, paletas, colorização | Game Boy |
| Sensor de inclinação/giro, sensor solar, rumble (vibração) | Sensores e vibração |
| Relógio de tempo real (RTC) | automático |

Do port: shaders de tela, layout livre (mover, redimensionar, esticar), monitor de desempenho,
controles na tela, gamepad/teclado e um banco de cheats com cerca de 2.970 jogos
([libretro-database](https://github.com/libretro/libretro-database), CC BY-SA 4.0).

## O que o mGBA de PC tem e este port ainda não

Cabo link / multiplayer, depurador e visualizadores (memória, tiles, mapas, sprites), scripts Lua,
gravação de vídeo e GIF, os shaders `.shader` do próprio mGBA, e-Reader, Game Boy Camera e Printer,
biblioteca de jogos, remapeamento de botões, mistura entre quadros, BIOS de GB/GBC e tradução da
interface.

## Estrutura

```
app/src/main/cpp/CMakeLists.txt   compila o mGBA como biblioteca estática + a ponte
app/src/main/cpp/bridge.c         ponte JNI: vídeo, áudio, estados, rebobinar, cheats, sensores
app/src/main/java/...             Native, EmuView, ScreenRenderer, Shaders, Cheats, PerfMonitor, MainActivity
scripts/                          baixam o mGBA e o banco de cheats em commits fixados
```

## Licença

Mozilla Public License 2.0, como o mGBA. Veja `LICENSE`.
