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

A tela segue a organização do PKHeX de PC: editor com abas de um lado, caixas e equipe do outro
(lado a lado com o celular deitado, um sobre o outro em pé).

- Abre um arquivo de save (qualquer formato que o `PKHeX.Core` reconheça).
- Caixas em grade de 6 colunas e equipe, com um quadro por espaço mostrando nome, nível, cor dos tipos,
  ★ para brilhante e ⚠ para quem não passa na checagem de legalidade.
- Toque num espaço: **Ver** (carrega no editor), **Colocar aqui** (grava o Pokémon do editor) ou **Apagar**,
  como o clique direito do PKHeX. Com isso dá para copiar e mover entre espaços.
- Editor em abas: Principal (espécie, apelido, nível, natureza, habilidade, item, forma, brilhante, amizade),
  Stats (IVs, EVs e stats calculados), Golpes e Origem (treinador original).
- Indicador de legalidade no editor, com o relatório do PKHeX.
- Janela do treinador (nome e dinheiro) e exportação do save como arquivo novo.
- Se o app fechar por erro, o texto do erro aparece na próxima abertura.

## O que ainda não tem

Imagens dos Pokémon (os quadros são coloridos pelos tipos, sem sprites), criar Pokémon do zero,
bola, local de encontro, mochila de itens, Pokédex, eventos, importar/exportar arquivos `.pk*`,
e os nomes do jogo em português (o PKHeX não tem essa tradução; aparecem em inglês).

## Licença

GPL v3 ou posterior, como o PKHeX. Veja `LICENSE`.
