# Arquitetura do Billing · Fase 4

Billing concentra cálculo, revisões e aprovação de orçamento, notificação ao cliente, checkout, confirmação financeira e publicação de eventos para a OS. A separação mantém as regras comerciais e o ciclo do Mercado Pago sob a mesma autoridade, enquanto a OS controla o reparo e a entrega.

## Componentes e dados

| Componente | Responsabilidade |
| --- | --- |
| `budget` | Casos de uso, domínio e adaptadores de criação, envio, aprovação, rejeição, notificações e fechamento comercial. |
| `payment` | Casos de uso de checkout e observação financeira, agregado de cobrança e tentativas, clientes Mercado Pago e webhook. |
| PostgreSQL exclusivo | `budget`, contador de revisão e fechamento da OS, autorizações e notificações, `charge`, `payment_attempt`, `billing_cancellation_receipt` e `integration_event_outbox`. Flyway mantém as migrations V1–V13 no código desta revisão. |
| Outbox e SQS | Orçamento ou pagamento e evento são gravados na mesma transação. O publicador busca registros disponíveis, envia à fila e registra o resultado. A Cloud pode habilitá-lo pelo ambiente. |

O orçamento congela o `compositionVersion` recebido da OS e consulta preços por lote no Inventory. Política de revisão e recibo de cancelamento impedem repetição com dados diferentes e criação tardia em contexto fechado. Aprovação presencial pode chegar à OS antes de um evento de envio. CT-10 permite reconciliar a revisão. O UUID de autoria declarado na aprovação presencial é dado de auditoria na revisão atual.

## Integrações da Saga

| Direção | Transporte e responsabilidade |
| --- | --- |
| Billing → OS | REST CT-02A para o estado da OS e CT-03 para a composição e contato do orçamento. |
| Billing → Inventory | REST `POST /internal/inventory/consultar-precos`. |
| OS → Billing | REST CT-07 quitação, CT-09 cancelamento de revisão, CT-10 revisão vigente, CT-11 fechamento mesmo sem orçamento conhecido. |
| Billing → OS | SQS com envio, aprovação e rejeição de orçamento, confirmação de pagamento e atualização de estorno integral confirmado. |
| Billing ↔ Mercado Pago | HTTPS para criar e consultar a Order. O webhook HMAC dispara consulta oficial antes de qualquer confirmação no banco. |
| Billing → e-mail | Notificação de orçamento com autorização temporária para decisão. O worker SMTP depende da configuração do ambiente. |

O checkout mantém chave idempotente e reserva durável. Se a criação externa ficar inconclusiva, a tentativa permanece com a identidade original. Antes de entregar, a OS consulta CT-07. O Billing responde conforme a cobrança confirmada que possui. Um estorno integral confirmado publica a atualização financeira, e a política de solicitar estorno permanece fora do fluxo de compensação pré-execução.

## Limites da revisão publicada

`develop` em `9d5eb18` usa correlação nas chamadas internas e já retirou Bearer do Billing. A OS e o Inventory ainda exigem credenciais nas rotas que o Billing consome. A homologação de pagamento de 12/11/2026 usou a implementação autenticada anterior. A jornada integrada precisa ser repetida com a combinação promovida. Endereços ClusterIP precisam de isolamento de rede e rotas internas fora da entrada pública. O transporte e o webhook Mercado Pago têm guarda exclusiva `local/TEST`.

Java 25, Spring Boot, PostgreSQL, Flyway e SDK SQS sustentam o runtime. O CI executa testes, JaCoCo com gate de 80%, Docker e validação Kubernetes. O Sonar é operado localmente nesta revisão. O Service renderizado expõe `numberone-billing-service:8080`. O deploy usa ECR por digest e o publicador da outbox é opt-in. A execução no EKS permanece uma evidência a completar.

[Visão das camadas](overview.md) · [Modelo de dados](data-model.md) · [Regras HTTP de orçamento](../integrations/budget-http-contracts.md) · [Pagamento HTTP](../integrations/payment-http-contracts.md) · [Collection Postman](../postman/numberone-billing.postman_collection.json) · [Eventos](../integrations/events.md) · [Qualidade e Sonar](../operations/quality.md) · [CI de 9d5eb18](https://github.com/PosTech15SOAT/numberone-billing-service/actions/runs/43628710642) · [Kubernetes](../../k8s/README.md)
