# Servidor WFC próprio no celular (com root)

O objetivo é fazer **batalhas e trocas reais**, de dentro do jogo (Wi-Fi Club, Pal Pad), entre um
Nintendo DS e o melonDS no celular, sem internet e sem servidores de terceiros. O celular vira o
"servidor da Nintendo".

## Como funciona

```
 DS (R4 / DSi)                        Celular com root
 ┌──────────────┐  DNS = IP do celular ┌───────────────────────────────────────┐
 │ jogo Pokémon │ ───────────────────► │ DNS (53→5353)   desvia *.nintendowifi │
 │ (ROM NoSSL)  │ ── HTTP 80 ────────► │ conntest + NAS (80→8080) login        │
 │              │ ── TCP 29900 ──────► │ GPCM: perfis, Pal Pad, status         │
 └──────────────┘                      │ GPSP 29901: busca de jogadores        │
                                       │ (fase B) QR2, Server Browser, NATNEG  │
 melonDS no mesmo celular ───────────► │                                       │
                                       └───────────────────────────────────────┘
```

O DS usa portas fixas (53 para DNS e 80 para HTTP), e um app comum do Android não pode abrir
portas abaixo de 1024. Por isso o servidor fica nas portas 5353 e 8080, e o app usa o **root** só para
criar regras de `iptables` (numa cadeia própria, `POKEBANK_WFC`) que desviam 53→5353 e 80→8080.
As regras valem apenas para tráfego destinado ao próprio celular e são removidas quando você para o
servidor.

O servidor roda como **serviço em primeiro plano** (com notificação fixa). Assim ele continua
funcionando enquanto você joga no melonDS, que fica na frente do PokeBank.

## O que já funciona (fase A)

| Parte | O que faz | Testado |
|---|---|---|
| DNS | responde `*.nintendowifi.net`, `*.gamespy.com` e `*.nintendo.net` com o IP do celular; o resto vai para a internet | sim (unidade) |
| conntest | o "Testar conexão" do DS | sim |
| NAS | criação de conta, login, `svcloc` (o Pokémon usa `svc=0000`), filtro de palavras | sim |
| GPCM | login com desafio/prova MD5, perfil, status, Pal Pad (pedido, aceite automático mútuo), mensagens diretas, mensagens guardadas para quem está offline | sim: dois "DS" simulados viram amigos e trocam status |
| GPSP | `otherslist` | sim |
| Root | `iptables` 53→5353 e 80→8080, com remoção ao parar | comandos verificados; execução só no seu celular |

Tudo é um porte em Kotlin do **AltWFC** (dwc_network_server_emulator), conferido contra o código
original. As funções criptográficas foram checadas com os próprios algoritmos do AltWFC.

## O que falta (fase B) para a batalha/troca acontecer

No Wi-Fi Club, depois do login, os dois jogos precisam se achar e abrir uma conexão direta. O jogo usa
três serviços que ainda não estão no PokeBank:

- **QR2** (UDP 27900): cada jogo anuncia "estou no Wi-Fi Club".
- **Server Browser** (TCP 28910): o jogo procura o amigo; usa a criptografia "enctypex" da GameSpy.
- **NATNEG** (UDP 27901): ajuda os dois a abrir o caminho direto um para o outro.

Eles somam cerca de 2.600 linhas no AltWFC. A fase B é portar esses três, do mesmo jeito.

## O patch NoSSL (obrigatório)

O jogo faz o login (NAS) por HTTPS e só aceita certificados da Nintendo. Para um servidor próprio, a
ROM precisa usar `http://`. No R4 e no melonDS isso é simples:

- **WfcPatcher** (PC, Windows): arraste a ROM e use a versão "(AltWfc)" gerada; ou
- **WfcReplay**: gera códigos de Action Replay para a sua ROM (alguns kernels de R4 aceitam cheats).

O "Testar conexão" funciona mesmo sem o patch, porque usa HTTP comum. O login no Wi-Fi Club precisa
do patch. Numa próxima versão, o próprio PokeBank pode aplicar esse patch.

## Passo a passo para testar a fase A

1. No celular com root, abra o PokeBank → aba **Servidor WFC** → **Iniciar**. Aceite o pedido do
   root (Magisk/KernelSU). Confira "Portas 53/80: desviadas (ok)".
2. Ligue o **roteador do celular sem senha**, em 2,4 GHz. O PokeBank mostra o IP do celular nessa rede.
3. No DS, abra o jogo → **Nintendo WFC Settings** → apague a configuração antiga (Options) → nova
   conexão na rede do celular → DNS automático **não** → Primário e Secundário = o IP mostrado.
4. Toque em **Testar conexão**. Se der certo, o registro do servidor mostra `DNS ... conntest` e
   `HTTP GET`. É o primeiro marco.
5. Com a ROM NoSSL: no jogo, entre no Wi-Fi Club (subsolo do Centro Pokémon). O registro deve
   mostrar `NAS action=login` e `GPCM: login de ...`, e o jogo recebe o código de amigo (aparece
   também na lista "Perfis registrados" do app).
6. Repita no melonDS com outro save. Registrem um ao outro no **Pal Pad** pelo código de amigo.
   O registro deve mostrar "agora são amigos".
7. Os dois entram no Wi-Fi Club: o status chega a cada um. A batalha/troca em si depende da fase B.

Mande o texto da aba "Servidor WFC" (o registro) em cada passo. É com ele que eu ajusto o servidor
ao comportamento real do jogo.

## Problemas conhecidos e cuidados

- Use **Magisk/KernelSU** com permissão para o PokeBank. Se `iptables` não tiver o módulo
  `addrtype`, as regras falham: o registro mostra o erro.
- Enquanto o servidor estiver ativo, os outros aparelhos no roteador do celular continuam com internet
  normal (o DNS repassa tudo que não é da Nintendo).
- O DS e o melonDS no **mesmo celular** usam caminhos de rede diferentes; o servidor anuncia o IP do
  celular para o melonDS. Isso ainda precisa ser confirmado na fase B.

## Créditos

Porte do AltWFC / dwc_network_server_emulator (github.com/polaris-/dwc_network_server_emulator, AGPLv3),
que por sua vez se baseia no OpenSpy e nos trabalhos de Luigi Auriemma sobre a GameSpy.
