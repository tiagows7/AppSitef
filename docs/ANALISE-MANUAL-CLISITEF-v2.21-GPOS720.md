# Análise — Manual CliSiTef Android v2.21 (GPOS720)

Fonte: `docs/CliSiTef - Interface com a aplicação - Android - v2.21.pdf`  
Texto extraído: `docs/manual-extracted.txt`

## Cenário do GPOS720

O GPOS720 é **Cenário 3** — APOS com **pinpad interno** (mesma família do GPOS700 citado no manual).

- **Seção 6** (pinpad USB/Bluetooth, `TipoPinPad=ANDROID_BT`, etc.) **não se aplica**.
- **Seção 7** é obrigatória para integração correta.

## Erro 31 — causas documentadas

### §7.1 — Pinpad virtual (`setActivity`)

> Se `setActivity` não for chamado no momento correto, a CliSiTef **não conecta ao pinpad interno** e devolve **Erro 31 (ERRO PINPAD)**.

Requisitos:

1. Chamar `clisitef.setActivity(activity)` na **Activity que faz a transação** (não na tela de configuração).
2. Recomendado no **`onCreate`** dessa Activity, **antes** de `startTransaction`.
3. A Activity deve ser a que implementa `ICliSiTefListener` e exibe o pinpad virtual.

**Correção no appsitef:** `TefTransactionActivity` chama `CliSiTefHolder.bindTransactionActivity(this)` em `onCreate`/`onResume`; configuração **não** chama `setActivity`.

### §7.2 — Bibliotecas Gertec

Para Gertec GPOS700 (referência no manual; GPOS720 segue o mesmo padrão):

| Requisito | Detalhe |
|-----------|---------|
| Classpath | `ppcomp-<versão>.aar` + `payment-<versão>.aar` (no projeto: JARs equivalentes) |
| Número de série | Terminal **sem serial** → Erro 31 |
| FactoryService | App **FactoryService** deve estar instalada no terminal |

O manual **não** manda chamar `PP_Open` antes da venda; o pinpad interno é via **pinpad virtual** do CliSiTef. O exemplo Kotlin oficial também não usa GEDI/PPComp na Activity de transação.

### §5 — Uma única instância

> Instancie **apenas uma vez** a CliSiTef, para evitar problemas de concorrência com o pinpad.

**Correção no appsitef:** `CliSiTefHolder` (singleton).

### §4 — `libclisitef.so` sem strip

Ausência de `doNotStrip` / `keepDebugSymbols` pode causar:

`Erro assinatura modulo (-158): libclisitef.so`

**Correção no appsitef:** `keepDebugSymbols += "**/libclisitef.so"` no `build.gradle`.

## Parâmetros adicionais do `configure()`

- **Pinpad externo (§6):** `[TipoPinPad=ANDROID_BT;]` etc.
- **Pinpad interno APOS (§7):** não usa esses parâmetros no `configure`.
- **TipoComunicacaoExterna** no manual aparece para **TLS/GSurf/COMNECT** no **CLSIT [Geral]** ou blocos específicos (§9), não para abrir pinpad Gertec.

No GPOS Delphi, `ParametrosAdicionais` na venda costuma ir **vazio**; `TEF_COMEXTERNA` do REST é outro conceito (comunicação externa SiTef), não substitui `setActivity`.

**Correção no appsitef:** com `COMEXTERNA = 0`, `configure()` recebe string vazia nos parâmetros adicionais.

## CLSIT (§8)

- Arquivo **`CLSIT`** (sem extensão) em **assets**.
- Copiado automaticamente para `/data/data/<package>/files` ao instanciar `CliSiTef`.
- `[PinPad]` com trace opcional; `DiretorioTrace=./files` recomendado.

## Checklist no terminal (se erro 31 persistir)

1. App Delphi TEF paga no **mesmo** GPOS720?  
   - Se sim → foco em `setActivity` / instância única / timing.  
   - Se não → FactoryService, serial Gertec, versões ppcomp/payment vs CliSiTef (`VERSIONS.TXT` do pacote homologado).

2. **FactoryService** instalada e ativa?

3. **Número de série** válido no terminal (menu Gertec / suporte)?

4. Reinstalar APK debug assinado Gertec após `Rebuild` (strip desativado).

5. Coletar trace (§8): habilitar `[CliSiTef]`/`[CliSiTefI]`/`[PinPad]` `HabilitaTrace=1` e enviar `.dmp` ao suporte Software Express.

## Referências no PDF

| Seção | Página (aprox.) | Assunto |
|-------|-----------------|---------|
| 7.1 | 19 | `setActivity` → erro 31 |
| 7.2 | 20 | Gertec ppcomp + payment, FactoryService, serial |
| 5.1 | 9–12 | Fluxo configure / startTransaction |
| 8 | 20–23 | CLSIT, trace |
| 6 | 15–17 | Só pinpad **externo** (ignorar no GPOS720) |
