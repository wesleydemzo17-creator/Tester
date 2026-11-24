# Protocolo PokeBank v1

Todos os números são **little-endian** (a ordem de bytes do DS).

## 1. Descoberta (UDP)

| Direção | Destino | Conteúdo |
|---|---|---|
| DS → rede | `255.255.255.255:47100` (broadcast), a partir da porta local 47102 | ASCII `PKBANK?1` (8 bytes) |
| Android → DS | resposta para o endereço de origem | `PKBANK!1` + porta TCP (u16) + nome do servidor (ASCII, até 32 bytes) |

O DS repete o broadcast a cada segundo. O Android precisa de um `MulticastLock` para receber broadcasts.

## 2. Quadros TCP (porta 47101)

```
offset  tamanho  campo
0       2        'P' 'B'
2       1        tipo
3       1        0 (reservado)
4       4        tamanho do payload (u32, no máximo 0x80000 + 4096)
8       n        payload
```

## 3. Mensagens

| Tipo | Nome | Direção | Payload |
|---|---|---|---|
| 0x01 | HELLO | DS → A | versão u8, tipo do chip u8 (3 = flash), reservado u16, tamanho do save u32, código do jogo [4], título [12], cartão IR u8 |
| 0x02 | HELLO_ACK | A → DS | versão u8 + nome do servidor (ASCII) |
| 0x03 | SAVE_DATA | DS → A | CRC32 do save u32 + save inteiro |
| 0x04 | MESSAGE | A → DS | texto ASCII para o DS mostrar |
| 0x05 | WRITE_REQUEST | A → DS | CRC32 do save novo u32, tamanho do resumo u32, resumo (ASCII, linhas com `\n`), save novo inteiro |
| 0x06 | WRITE_RESULT | DS → A | status u8, CRC32 do que foi lido de volta u32, setores gravados u16 |
| 0x07 | REQUEST_SAVE | A → DS | vazio: o DS relê o cartucho e manda SAVE_DATA de novo |
| 0x09 | BYE | ambos | vazio |

O CRC32 é o padrão (zlib, polinômio 0xEDB88320).

### Status de WRITE_RESULT

| Valor | Significado | O que o Android faz |
|---|---|---|
| 0 | OK: gravado e conferido | confirma depósitos e retiradas no banco |
| 1 | Cancelado no DS (ou pedido inválido); nada foi gravado | descarta o diário |
| 2 | Falhou, mas o DS regravou e conferiu o save original | descarta o diário |
| 3 | Falhou e o estado do cartucho é incerto | mantém o diário e o backup e avisa o usuário |

## 4. Sequência típica

```
DS                                  Android
 |-- UDP "PKBANK?1" (broadcast) ---->|
 |<--------- "PKBANK!1" + porta -----|
 |-- TCP connect ------------------->|
 |-- HELLO ------------------------->|
 |<-------------------- HELLO_ACK ---|
 |-- SAVE_DATA (512 KB) ------------>|  backup + decodifica
 |<---------------------- MESSAGE ---|
 |        ... usuário mexe no celular ...
 |<---------------- WRITE_REQUEST ---|
 |  (usuário confirma: A, depois R+A)
 |  apaga/grava/confere os setores de 64 KB que mudaram
 |-- WRITE_RESULT ------------------>|  confirma no banco
```

## 5. Gravação no chip

Comandos SPI usados no chip flash: `0x9F` (ID), `0x03` (ler), `0x05` (status), `0x06` (habilitar
escrita), `0xD8` (apagar setor de 64 KB) e `0x02` (programar página de 256 bytes). Em cartões com
infravermelho (HGSS), cada comando é precedido de um byte `0x00` para liberar o acesso ao chip
(modo experimental).

Ideias para versões futuras do protocolo: transferir só o bloco das caixas (~74 KB) em vez dos
512 KB, mandar a lista de Pokémon para o DS mostrar, e trocas ao vivo entre dois DS pelo celular.
