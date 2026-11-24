# Trocas e batalhas: o plano

O PokeBank já tem a base: o celular lê, entende e grava saves de 4ª geração vindos de **três
lugares**. São eles o cartucho original e o arquivo `.sav` do R4/DSi (ambos pelo DS, via Wi‑Fi) e
o save de um emulador no próprio celular.

## Precisa criar um emulador?

**Não.** Já existem emuladores de DS de código aberto para Android e eles fazem isso muito bem:

- **melonDS para Android** (código aberto, gratuito) — salva em `.sav` puro.
- **DraStic** — salva em `.dsv` (save + rodapé de 122 bytes, que o PokeBank preserva).

Escrever outro emulador do zero levaria anos e não traria nada novo. O que faltava era a **ponte**
entre o DS de verdade e o emulador, e é isso que o PokeBank faz:

```
DS (cartucho / R4 / DSi) --Wi-Fi--> PokeBank (banco) <--arquivo--> emulador no celular
```

Cuidados com o emulador:
- **Feche o jogo no emulador** antes de abrir o save no PokeBank.
- Depois de "Salvar no arquivo", abra o jogo de novo. Se o emulador estiver com o jogo aberto, ele
  pode sobrescrever a mudança.

## Fase 1 — pronta (esta versão)

- **Troca entre cartuchos e emulador:** deposite no banco a partir de um save e retire em outro.
  Funciona entre DS e DS, DS e emulador, emulador e emulador, e entre jogos diferentes (DP, Pt, HGSS),
  com bloqueio de itens e formas que não existem no jogo de destino.
- **Pokédex completa e ficha de cada Pokémon:** golpes, IVs, EVs, status, natureza, habilidade,
  origem, fitas, Pokérus, e sprites extraídos da sua ROM.
- **Exportação para o Pokémon Showdown:** a equipe vira texto no formato do simulador de batalhas
  de código aberto, para batalhar online já hoje.

## Fase 2 — troca ao vivo (protocolo v2)

Hoje a troca passa pelo banco: primeiro você deposita, depois retira. A troca ao vivo faz as duas
coisas de uma vez, como no jogo:

1. Cada lado (um DS ou o celular) escolhe um Pokémon e manda um `TRADE_OFFER` com os 136 bytes do
   `.pk4` e um resumo.
2. Os dois veem o Pokémon do outro (com a ficha completa no celular) e confirmam com `TRADE_ACCEPT`.
3. O servidor (celular) monta os dois saves novos e manda um `WRITE_REQUEST` para cada lado.
4. Só quando **os dois** responderem `WRITE_RESULT = OK` a troca é registrada. Se um lado falhar, o
   outro volta ao save original (o celular guarda os dois backups).

Mensagens novas (rascunho):

| Tipo | Nome | Payload |
|---|---|---|
| 0x10 | TRADE_OFFER | caixa u8, slot u8, pk4[136] |
| 0x11 | TRADE_PEER | pk4[136] do outro lado + nome do treinador |
| 0x12 | TRADE_ACCEPT / 0x13 TRADE_CANCEL | vazio |

Para dois DS trocarem entre si, o celular fica no meio (relay), com os dois conectados ao mesmo
servidor. O servidor atual atende um DS por vez; a fase 2 passa a atender dois.

**Evoluções na troca:** Pokémon que evoluem por troca (Kadabra, Machoke, Haunter, Graveler, Onix com
Metal Coat etc.) **não** evoluem sozinhos aqui, porque quem evolui é o jogo. Na fase 2 o celular pode
oferecer a evolução: trocar a espécie e recalcular os dados, como o jogo faz.

## Fase 3 — batalhas DS ↔ Android

A forma realista de ter batalhas fiéis às regras da 4ª geração:

- **Motor de batalha no celular:** o simulador do Pokémon Showdown (código aberto, JavaScript),
  rodando num interpretador JS embutido no app (QuickJS, por exemplo), no modo Gen 4. Ele já
  implementa todas as regras, danos, habilidades e itens.
- **O DS vira o "controle" e a tela:** mostra o seu Pokémon, o do adversário, HP e golpes, e manda a
  escolha (golpe ou troca). Não é preciso reescrever o motor de batalha no DS.
- **Times reais:** saem direto do save (equipe ou caixas), com a mesma exportação que já existe.

Mensagens novas (rascunho):

| Tipo | Nome | Payload |
|---|---|---|
| 0x20 | BATTLE_START | formato (simples/dupla), time do adversário (espécies visíveis) |
| 0x21 | BATTLE_STATE | estado compacto: HP, status, quem está em campo, golpes e PP disponíveis |
| 0x22 | BATTLE_CHOICE | DS → celular: golpe 1-4, troca 1-6 |
| 0x23 | BATTLE_LOG | texto curto para a tela de cima do DS |
| 0x24 | BATTLE_END | vencedor |

Modos possíveis: DS contra o celular (você contra a IA ou contra outra pessoa no celular), DS contra
DS com o celular como juiz, e celular contra celular.

## Fase 4 — recursos online do jogo original (avançado)

Os jogos de 4ª geração têm troca e batalha por Wi‑Fi (Nintendo WFC). Dá para fazer o celular se passar
pelo servidor da GTS: o DS aponta o DNS para o celular, e o app recebe e envia Pokémon pela própria
GTS do jogo, sem homebrew no DS. Já existiram projetos assim (por exemplo, o IR‑GTS). A limitação é a
mesma do Wi‑Fi do DS: só redes abertas ou WEP.

## Ordem sugerida

1. Testar a fase 1 no seu DS (R4 e DSi) e no emulador.
2. Fase 2, troca ao vivo DS ↔ celular.
3. Fase 3, primeiro DS contra celular, com o simulador embutido.
4. Fase 4, se você quiser usar a GTS do próprio jogo.
