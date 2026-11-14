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

- **MENU → Abrir ROM (.nds)**: escolha um arquivo `.nds` (descompacte `.zip`/`.7z` antes). A ROM é copiada para a pasta do app.
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
| Frameskip | Automático (pula até 3 quadros quando atrasa) ou fixo de 0 a 4. |
| Vídeo | Quadro em RGB565 direto do buffer nativo, escalado pela GPU (`lockHardwareCanvas`). |
| Áudio | Mixagem assíncrona, uma fatia por quadro, direto para `AudioTrack`. |
| Compilação | `-O3`, somente `arm64-v8a`, páginas de 16 KB suportadas. |
| Avanço rápido | No menu, com o jogo aberto. |

## Limitações conhecidas

- Somente aparelhos ARM de 64 bits, Android 8.0+.
- 3D apenas por software (sem OpenGL ES ainda) e em resolução nativa.
- Sem microfone, Wi-Fi, cheats e filtros de imagem.
- Sem BIOS/firmware externos (usa a emulação HLE do núcleo).

## Estrutura

```
app/src/main/cpp/CMakeLists.txt   build do núcleo + ponte (também compila em Linux para testes)
app/src/main/cpp/bridge.cpp       ponte JNI: vídeo, áudio, entrada, saves
app/src/main/cpp/shim/            substitutos mínimos de glib e libpcap
app/src/main/java/...             Native, EmuView (telas, controles, loop), MainActivity (menu)
.github/workflows/build.yml       gera o APK no GitHub Actions
```

## Licença

GPL v2 ou posterior, como o DeSmuME. Veja `LICENSE`.
