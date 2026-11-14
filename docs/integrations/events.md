# Eventos de integração

## Produtores, consumidores e envelope

Billing publica cinco tipos de evento por sua Outbox. O consumidor de negócio esperado é OS Service; sua implementação/Inbox não está neste repositório. Billing não consome eventos SQS e não cria cobrança automaticamente ao receber `OSConcluida`.

Todos os envelopes têm exatamente estes campos na raiz, conforme `BudgetIntegrationEventFactory` e `PaymentIntegrationEventFactory`:

| Campo | Tipo JSON | Origem e semântica |
| --- | --- | --- |
| eventId | string UUID | Gerado uma vez ao construir o fato; preservado em retries |
| eventType | string | Um dos tipos da tabela abaixo |
| schemaVersion | inteiro | 1 nos eventos atuais |
| occurredAt | string Instant UTC | Budget: instante de criação do fato, microssegundos; Payment: `Charge.updatedAt` da quitação |
| correlationId | string UUID | Budget: correlação da operação quando disponível; Payment: `chargeId` estável |
| payload | objeto | Campos específicos abaixo |

Budget usa a correlação da operação presente no MDC, ou gera UUID quando não há contexto; Payment usa a cobrança. Não são identificadores de tracing.

| eventType | Gatilho confirmado | Payload completo |
| --- | --- | --- |
| OrcamentoEnviado | DRAFT → SENT por solicitação de aprovação | serviceOrderId, budgetId, budgetVersion, compositionVersion |
| OrcamentoAprovado | Aprovação integral legada, presencial ou pública | serviceOrderId, budgetId, budgetVersion, compositionVersion, approvedAmount, currency |
| OrcamentoRejeitado | Rejeição legada ou pública | serviceOrderId, budgetId, budgetVersion, compositionVersion |
| PagamentoConfirmado | Primeira confirmação efetiva OPEN → PAID | serviceOrderId, paymentId, paymentStatus, paymentVersion |
| PagamentoAtualizado | Estorno integral PAID → REFUNDED | serviceOrderId, paymentId, paymentStatus, paymentVersion |

## Payloads Budget

`serviceOrderId` e `budgetId` são UUIDs; `budgetVersion` e `compositionVersion` identificam respectivamente a revisão do orçamento e a composição da OS. `approvedAmount` é string decimal e `currency` é `BRL` na aprovação com composição versionada. A fábrica preserva a serialização de dados históricos sem versão de composição; novos orçamentos exigem versão positiva e valores em centavos exatos.

Criar DRAFT e reenviar aprovação não geram evento. A aprovação presencial de DRAFT faz SENT intermediário apenas em memória e grava somente `OrcamentoAprovado`: `OrcamentoEnviado` pode nunca existir para essa revisão. Não há campo no envelope que identifique o canal. O consumidor deve definir obtenção do contexto e não esperar indefinidamente um envio inexistente. Duplicidade/desordem não pode regredir aprovação/rejeição para SENT ou revisão antiga.

## Payload Payment

| Campo | Tipo JSON | Valor implementado |
| --- | --- | --- |
| serviceOrderId | string UUID | OS da cobrança |
| paymentId | string UUID | `Charge.chargeId`, não `attemptId`, ID financeiro ou entidade Payment |
| paymentStatus | string | PAID ou REFUNDED |
| paymentVersion | inteiro | 1 para quitação, 2 para estorno integral; independente de `row_version` |

Não há valor, moeda, order, pagamento do provedor, parcelas ou meio nesse payload. O contrato externo de `PagamentoConfirmado`, sua correlação local e sua aplicação na projeção OS ainda precisam de confirmação. A existência do publicador não comprova consumidor financeiro externo.

Replay equivalente preserva o evento já criado. PAID histórico sem Outbox não recebe backfill. A V12 exige `charge_id` e referências Budget nulas na linha financeira da Outbox, embora o orçamento permaneça vinculado à cobrança. Esses campos de indexação não são propriedades extras do envelope.

## Entrega e responsabilidades externas

O envelope é armazenado com o estado na transação PostgreSQL e enviado posteriormente como corpo SQS Standard. Há unicidade local por Budget/tipo e Charge/tipo, mas a publicação e a entrega são duplicáveis. `PUBLISHED` significa aceite SQS confirmado localmente, sem afirmar aplicação na OS. Veja [claims, tentativas e falhas](../flows/outbox.md).

O consumidor deve deduplicar `eventId` atomicamente com seu efeito, tratar conteúdo divergente sob mesmo ID como conflito e validar revisão/estado para mensagens atrasadas. A estratégia definitiva de eventos sem contexto, aprovação sem envio e quitação precisa de acordo com OS Service. Fila, DLQ, IAM, retenção e alarmes AWS também não são provisionados pelo Billing.

Não há coordenador Saga, compensação distribuída ou exactly-once. Os TODOs de Saga no código indicam trabalho pendente; não representam um protocolo já integrado. As [pendências](../technical-debt.md) separam contratos externos de recuperação local.

Fontes: [fábrica Budget](../../src/main/java/br/com/fiap/numberone/billing/budget/infrastructure/outbox/BudgetIntegrationEventFactory.java), [teste de envelope Budget](../../src/test/java/br/com/fiap/numberone/billing/budget/infrastructure/outbox/BudgetIntegrationEventFactoryTest.java), [fábrica Payment](../../src/main/java/br/com/fiap/numberone/billing/payment/infrastructure/outbox/PaymentIntegrationEventFactory.java), [PaymentConfirmationIT](../../src/test/java/br/com/fiap/numberone/billing/payment/infrastructure/persistence/PaymentConfirmationIT.java), [ADR 0011](../adr/0011-integral-payment-confirmation-and-outbox.md).
