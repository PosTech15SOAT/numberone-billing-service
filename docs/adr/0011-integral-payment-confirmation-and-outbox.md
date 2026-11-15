# ADR 0011: Quitação integral de uma transação e evento na Outbox

**Status:** Aceita — política do principal evoluída conforme histórico abaixo.

**Data:** 2026-11-09

## Contexto

Charge contém tentativas com um único providerPaymentId imutável. A observação inicial admitia somar transações, mas não determinava qual identidade financeira conservar. Confirmar localmente sem registrar intenção de evento também permitiria perder a comunicação após queda. A Outbox PostgreSQL e o publicador já existiam.

## Decisão

Limitar quitação automática a uma transação integral, com order/pagamento processed/accredited, ID/referência/BRL consistentes e sem refunds/chargebacks. OrderPaymentEvidence observa o snapshot externo; APPROVED é dado de aplicação, sem mutação por si.

ConfirmPaymentUseCase encaminha essa evidência ao ChargeGateway. O adaptador obtém FOR UPDATE na raiz, relê histórico e revalida identidades e condições com a mesma política. A operação anterior que aceitava apenas IDs foi substituída pelo caminho com evidência. Domínio aplica a transição permitida e conserva identidades/valor.

Primeira OPEN → PAID atualiza raiz/tentativa e insere PagamentoConfirmado na mesma transação. V12 acrescenta charge_id opcional/FK/check de referência e índice único por cobrança/tipo à Outbox existente. Não criar tabela/worker financeiro paralelo; preservar append de Budget e reutilizar o publicador. paymentId=chargeId, paymentVersion=1 representa a primeira quitação comercial, independente de @Version. correlationId é a cobrança local, sem equivalência com trace/request-id.

Replay equivalente não altera datas/versão/evento. Divergência financeira não substitui IDs. PAID histórico sem evento não recebe backfill automático. Falha de flush/append/commit ou rollback chamador reverte todos os efeitos. PAYMENT_CONFIRMED ocorre após commit; publicação posterior é pelo menos uma vez. Observações não aprovadas não liberam reserva nem gravam FAILED/EXPIRED/estorno automaticamente.

## Histórico da política monetária

A regra anterior exigia igualdade também entre principal e totais pagos. Uma amostra TEST com principal 10.00, uma transação de valor 10 e totais pagos 10.96 não atendia a essa regra; sem decomposição contratual, não se classificou a diferença como juros.

A revisão de 2026-11-10 passou a quitar o principal integral coberto: Charge.amount = order.total_amount = payment.amount; paid_amount e total_paid_amount cobrem seus principais e são iguais entre si. O excedente coerente não é classificado. Permanecem transação única, identidade, moeda, estados e ausência de ajustes. Sem tolerância/arredondamento, nova entidade, status ou migration. A [fixture sanitizada](../../src/test/resources/payment/checkout-pro-paid-installments.json) documenta a representação exercitada.

Essa política não resolve composição contábil, múltiplas transações, efeitos posteriores ao snapshot ou evidência durável de parcelas. Habilitação operacional inicialmente bloqueada evoluiu para local/TEST na ADR 0012, sem liberação EKS.

## Alternativas consideradas

- **Somar transações e escolher uma identidade:** rejeitada; perde correspondência financeira.
- **Criar Payment e histórico de parcelas:** ampliação sem regra/consumidor atual.
- **Confiar só na observação antes do lock:** não garante compatibilidade com estado atual.
- **Evento após commit sem Outbox:** janela de perda; escolhida intenção no mesmo commit.
- **Novo publicador financeiro:** duplicaria mecanismo já necessário e implementado.
- **Backfill por replay:** inventaria instante/identidade e poderia repetir integração histórica.

## Consequências

Quitação e intenção de evento são atômicas localmente, com replay/concorrência protegidos e infraestrutura reutilizada. Falha da Outbox impede a quitação local. Publicação pode duplicar e requer consumidor idempotente. Contrato de PagamentoConfirmado/projeção OS, recebimento durável e recuperação histórica continuam pendentes; não se presume homologação externa a partir de testes PostgreSQL/HTTP simulados.

## Referências

- [ADR 0004](0004-transactional-outbox.md), [ADR 0008](0008-payment-aggregate-and-local-concurrency.md), [ADR 0010](0010-durable-checkout-reservation.md), [ADR 0012](0012-local-test-seller-verification.md).
- [Fluxo financeiro](../flows/payment.md), [eventos](../integrations/events.md), [V12](../../src/main/resources/db/migration/V12__payment_confirmation_outbox.sql).
