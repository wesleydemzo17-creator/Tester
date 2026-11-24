# PokeBank — banco de Pokémon entre Nintendo DS e Android

Projeto nº 9: um programa homebrew para o Nintendo DS lê o save do cartucho de Pokémon e conversa
por Wi‑Fi com um app Android. No celular você vê as 18 caixas do PC, **deposita** Pokémon no banco
do celular e **retira** de volta, para o mesmo cartucho ou para outro (o que na prática é uma troca
entre cartuchos).

Jogos suportados: **Diamond, Pearl, Platinum, HeartGold e SoulSilver** (4ª geração, save flash de 512 KB).

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

1. **Rede.** O DS precisa de Wi‑Fi de 2,4 GHz.
   - **DS / DS Lite:** só conecta em redes **abertas ou WEP**. Use o roteador do celular sem senha
     (muitos Androids permitem "Segurança: nenhuma") ou um roteador antigo.
   - **DSi / 3DS em modo DSi:** aceita WPA2.
   - Configure a conexão uma vez em "Nintendo WFC Settings", no menu de um jogo Pokémon, ou nas
     configurações de internet do DSi. O PokeBank DS usa essa configuração salva.
2. Abra o app **PokeBank** no celular. Ele mostra o IP e fica esperando.
3. Inicie o **PokeBank DS** pelo flashcart ou pelo TWiLight Menu++.
   - **DS Lite com flashcart no Slot‑1:** depois que o programa abrir, ele pede para você trocar o
     cartão. Tire o flashcart e coloque o cartucho Pokémon. É a mesma técnica de ferramentas de
     backup de save; faça por sua conta e risco.
   - **DSi / 3DS rodando pelo cartão SD:** é só deixar o cartucho Pokémon no Slot‑1.
4. O DS lê o save, acha o celular sozinho (ou aperte **Y** para digitar o IP) e envia o save.
   O celular faz **backup automático** em `Android/data/br.pokebank/files/bank/backups/`.
5. No celular:
   - **Aba Cartucho:** toque num Pokémon e escolha "Depositar no banco".
   - **Aba Banco:** toque num Pokémon e escolha "Retirar para o cartucho".
6. Toque em **Gravar no DS**. O DS mostra o resumo e pede **A**, depois **segurar R + A**.
   Ele grava só os setores que mudaram, lê tudo de volta e confere.
7. Só depois da confirmação do DS o banco do celular é atualizado.

## Segurança do save

- Todo save recebido vira um backup antes de qualquer outra coisa.
- O app só altera o bloco das caixas (Storage) e recalcula o checksum dele, como o PKHeX faz.
- O DS grava setor por setor, confere cada um, e se algo falhar **regrava o save original**.
- Depósitos enviados ficam num diário (`bank/pending`). Se o app fechar no meio de uma gravação, ele
  avisa e deixa você recuperar esses Pokémon.
- Pokémon com itens ou formas que não existem no jogo de destino são bloqueados (por exemplo,
  Giratina Origem ou um item de HGSS indo para Diamond/Pearl).
- **Teste primeiro com um cartucho cujo save você já copiou por outro meio.**

## Limitações desta versão (v1)

- Só as caixas do PC, sem a party (Pokémon da party têm estatísticas calculadas que precisariam ser geradas).
- **Cartuchos com infravermelho (HeartGold/SoulSilver):** o acesso ao chip de save por trás do IR é
  experimental. A leitura é validada pelo celular (o save só é aceito se os rodapés e checksums
  baterem), então teste a leitura antes de gravar.
- O app não verifica legalidade (não é um PKHeX). Ele move os Pokémon byte a byte, sem alterar nada.
- A tela do DS usa a fonte padrão, sem acentos.

## Testado até aqui (sem hardware)

- Testes de unidade do núcleo com arquivos `.pk4` reais dos testes do PKHeX: checksum, nomes
  (inclusive japonês), ovos, criptografia nas 32 ordens de blocos, detecção de DP/Pt/HGSS e da
  partição ativa, e o ciclo depositar → gravar → retirar.
- Ponta a ponta: o servidor Kotlin real conversando com o simulador do DS nos cenários de gravação
  normal, cancelamento no DS e chip com defeito (com restauração do original).
- O código do DS foi checado (tipos e sintaxe) contra os headers reais da libnds, do calico e do
  dswifi. **Ainda não foi compilado com o devkitARM nem rodado num console**: esse é o próximo passo, com você.

## Créditos e licença

- Layouts do save, criptografia PK4, tabela de caracteres, nomes de espécies e curvas de experiência
  foram conferidos e extraídos do **PKHeX** (https://github.com/kwsch/PKHeX, GPLv3). Por isso este
  projeto deve ser distribuído sob a **GPLv3**.
- Exemplos e bibliotecas do devkitPro: https://github.com/devkitPro/nds-examples.
- Pokémon é marca da Nintendo/Game Freak/The Pokémon Company. Projeto de fã, sem fins comerciais.
