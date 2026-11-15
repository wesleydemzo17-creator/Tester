# SnapForge

Painel de jogos para aparelhos Snapdragon com root (Magisk / KernelSU). Android 8+.

## Funções (v0.2)

- **Painel**: temperatura de CPU/GPU/bateria, frequência por cluster, uso da GPU Adreno, RAM, corrente da bateria.
- **Modos de desempenho**: Economia, Equilibrado, Desempenho, Extremo (cpufreq + GPU kgsl).
- **Biblioteca de jogos**: detecta jogos, permite adicionar qualquer app, modo por jogo aplicado ao abrir.
- **Ajustes**: taxa de atualização, pausar carregamento, limpar RAM, restaurar padrões.
- **Controle**: ligar/desligar núcleos, frequência mínima/máxima e governor por cluster, níveis e governor da GPU.
- **Overlay flutuante**: uso e temperatura de CPU/GPU, mini gráfico, print e gravação de tela; transparência e tamanho ajustáveis.
- **Bloco nas Configurações Rápidas** para trocar de modo sem abrir o app.

Nada é reaplicado no boot: reiniciar o aparelho desfaz qualquer ajuste.

## Compilar pelo GitHub

1. Envie todo o conteúdo desta pasta para a raiz do repositório (incluindo `.github/`).
2. Abra a aba **Actions** → **Build APK**. Ele roda a cada push; também dá para usar **Run workflow**.
3. Ao terminar, baixe o artefato **SnapForge-debug** (um zip com o APK).

Cada build do Actions usa uma chave de debug nova: para atualizar, desinstale a versão anterior antes.

## Estrutura

- `Root.kt` — shell `su` persistente
- `Monitor.kt` — leitura de sensores
- `Perf.kt` — modos de desempenho
- `Tools.kt` — tela, carregamento, memória
- `Games.kt` — biblioteca de jogos
- `MainActivity.kt` — interface (Jetpack Compose)
- `Tuning.kt` / `TuningScreen.kt` — controle manual de CPU/GPU e cartão do overlay
- `OverlayService.kt` — overlay flutuante
- `ModeTileService.kt` — bloco das Configurações Rápidas
