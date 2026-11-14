# PKHeX para Android (port de teste)

Editor de saves para Android que usa o núcleo oficial do PKHeX
([kwsch/PKHeX](https://github.com/kwsch/PKHeX), GPL v3), a biblioteca `PKHeX.Core`, com uma
interface nova feita em .NET para Android.

O código do PKHeX **não** está neste repositório: `scripts/fetch_pkhex.sh` baixa o commit fixado
`c758bac1` do repositório oficial na hora do build. Nenhum arquivo dele é modificado. A interface
do PKHeX para Windows (WinForms) e as imagens dos sprites não são usadas; o app é só texto.

## Como gerar o APK

Cada push nesta branch (`pkhex-android`) roda o workflow **Build PKHeX APK** no GitHub Actions.
Abra a execução, baixe o artefato **pkhex-android-apk**, descompacte e instale o APK.

No PC: .NET SDK 10 com `dotnet workload install android`, depois

```bash
./scripts/fetch_pkhex.sh
dotnet build PKHeXAndroid/PKHeXAndroid.csproj -c Release
```

## O que esta versão faz

- Abre um arquivo de save (qualquer formato que o `PKHeX.Core` reconheça).
- Mostra jogo, treinador, ID, dinheiro e tempo de jogo.
- Lista as caixas e a equipe, marcando brilhantes e Pokémon que não passam na checagem de legalidade.
- Edita um Pokémon: espécie, apelido, nível, brilhante, natureza, item, golpes, IVs, EVs e amizade.
- Mostra o relatório de legalidade do PKHeX.
- Edita nome e dinheiro do treinador.
- Exporta o save editado como um arquivo novo (o original não é alterado).

## O que ainda não tem

Criar Pokémon em espaço vazio, mover entre caixas, formas, habilidade, bola, local de encontro,
mochila de itens, Pokédex, eventos, importar/exportar arquivos `.pk*`, e os nomes do jogo em
português (o PKHeX não tem essa tradução; os nomes aparecem em inglês).

## Licença

GPL v3 ou posterior, como o PKHeX. Veja `LICENSE`.
