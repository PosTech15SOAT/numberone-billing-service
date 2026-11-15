# ADR 0008: Agregado de cobrança e concorrência local de pagamentos

**Status:** Aceita — evoluída pelos complementos abaixo e pelas ADRs 0009–0012.

**Data:** 2026-11-08

## Contexto

O Billing precisa cobrar uma OS concluída pelo orçamento aprovado, permitir tentativas sucessivas e impedir duas operações financeiras válidas simultâneas entre réplicas. Uma resposta desconhecida do provedor não autoriza uma segunda operação. A decisão inicial tratava somente domínio/persistência, sem HTTP, provedor, webhook ou evento financeiro.

## Decisão original

Usar `Charge` como raiz e `PaymentAttempt` como entidade interna. Valor BigDecimal e Currency explícita são conservados na cobrança. Não criar entidade Payment ou repositório de domínio independente para tentativa. Mutação de tentativa passa pela raiz.

Estados: Charge OPEN/PAID; tentativa PENDING/UNKNOWN/PAID/FAILED/EXPIRED. PENDING nasce antes de I/O; UNKNOWN registra incerteza. PENDING, UNKNOWN e PAID bloqueiam nova tentativa. Só falha/expiração confirmadas permitem nova chave; sem expiração por relógio ou retry automático.

Persistir charge e payment_attempt com FK local para Budget e da tentativa para Charge, sem FK para OS externa. Uma UNIQUE vitalícia por service_order_id limita o MVP a uma cobrança por OS. INSERT ON CONFLICT DO NOTHING recupera a raiz concorrente; orçamento/valor/moeda divergentes geram conflito. Valor vem do Budget aprovado da mesma OS sob FOR SHARE; contexto de OS deve ser COMPLETED, obtido pela integração antes da transação.

Chave UUID global por tentativa e ID financeiro único/imutável quando conhecido. Na modelagem inicial, o meio já era informado ao criar tentativa e fazia parte da equivalência; o complemento abaixo substituiu esse requisito.

Mutações adquirem FOR UPDATE na raiz antes da reconstrução; leituras usam FOR SHARE para coerência entre raiz/histórico em READ COMMITTED. Entidades já gerenciadas são recarregadas após lock. @Version fica na raiz JPA. Toda mutação efetiva da tentativa atualiza a raiz em microssegundos monotônicos; replay não altera datas/versão. Índice parcial único bloqueia PENDING/UNKNOWN/PAID simultâneos. Falha/rollback desfaz raiz/histórico/versão.

A decisão inicial admitia moeda/precisão explícitas sem impor BRL/centavos. A confirmação era local, sem jornada externa integrada; a liberdade monetária e a ausência de integração foram revistas em decisões posteriores, não capacidades presentes desde o início.

## Alternativas consideradas

- **Tentativa como agregado independente:** exigiria coordenação adicional para bloquear operação e confirmar quitação, sem consumidor independente.
- **Somente optimistic locking:** detectaria disputa depois da reconstrução; escolhido lock da raiz e proteção JPA adicional.
- **Somente memória/domínio:** insuficiente entre réplicas; complementado por constraints/locks PostgreSQL.
- **Somente constraints:** não aplica todas as transições nem decide sobre agregado atualizado.
- **Cobranças substitutas:** exigiria regras de invalidação/substituição fora do MVP; mantida unicidade vitalícia por OS.

## Consequências

Domínio permanece Java puro, entidades JPA separadas e operações independentes podem avançar por cobrança. Leituras podem aguardar mutações; transações precisam ser curtas e sem I/O remoto. FKs não comparam valor/estado/OS do Budget, validados pelo adaptador. Escrita SQL fora dele pode violar coerência entre raiz e histórico. Múltiplos provedores/contas e cobranças substitutas exigiriam rever unicidades. PENDING abandonado/UNKNOWN bloqueiam indefinidamente sem recuperação implementada.

## Evolução — Checkout Pro (2026-11-08)

Escolhido Checkout Pro Orders API, com meio definido pelo cliente posteriormente. `startAttempt` deixa de receber método; `paymentMethod` passa a ser anulável. Valores PIX/CARD já conhecidos permanecem e não podem ser trocados. A descoberta do meio não troca chave.

V10 adiciona providerOrderId e checkoutUrl opcionais, separando order de transação financeira. Associação é controlada pela raiz, idempotente se equivalente e não substitui identidade/URL conhecidas. A primeira associação/enriquecimento em UNKNOWN pode resolver criação para PENDING; replay de metadados equivalentes não desfaz UNKNOWN posterior. Metadados não reabrem resultado financeiro terminal. Criar order não quita.

A política de novas operações passa a exigir valor positivo em BRL e precisão de centavos sem arredondamento, substituindo a liberdade original. A intenção foi descrita como escala de até duas casas; o código vigente usa setScale(2, UNNECESSARY), aceita zeros finais excedentes e conserva a escala original. O mapper de criação ainda exige scale() <= 2, divergência registrada em [débitos](../technical-debt.md#precisão-e-dados-históricos). Histórico/restauração mantêm registros da V9; V10 não impõe correção retroativa de moeda/escala.

V10 conserva checks/índices financeiros da V9. Domínio valida estrutura HTTPS, host presente, sem credenciais/fragmento, URL até 2048 e IDs até 255; allowlist de host pertence ao adaptador. Reserva durável veio na ADR 0010; confirmação e evento na ADR 0011; habilitação local na ADR 0012.

## Referências

- [Domínio atual](../architecture/domain-model.md), [persistência](../architecture/data-model.md).
- [V9](../../src/main/resources/db/migration/V9__create_charges_and_payment_attempts.sql), [V10](../../src/main/resources/db/migration/V10__adapt_payment_attempts_for_checkout_pro.sql).
- [ADR 0001](0001-billing-database.md), [ADR 0003](0003-budget-optimistic-locking.md), [ADR 0004](0004-transactional-outbox.md).
