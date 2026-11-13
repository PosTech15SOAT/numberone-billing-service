# Observabilidade

## Health e probes Kubernetes

O Actuator expõe somente health por HTTP, na mesma porta da aplicação (8080 por padrão), sem detalhes/componentes internos, descoberta em `/actuator` ou exposição JMX. Os paths abaixo foram verificados nos testes HTTP com a configuração da aplicação:

| Path (GET) | Comportamento |
| --- | --- |
| `/actuator/health` | Saúde agregada dos indicadores disponíveis, incluindo PostgreSQL. Retorna 200 quando UP e 503 para DOWN/OUT_OF_SERVICE. |
| `/actuator/health/liveness` | Somente `livenessState`: 200 em CORRECT, 503 em BROKEN. Não consulta dependências externas. |
| `/actuator/health/readiness` | Somente `readinessState`: 200 em ACCEPTING_TRAFFIC, 503 em REFUSING_TRAFFIC, conforme o ciclo de vida da aplicação. Não consulta dependências externas. |

Readiness não inclui banco ou integrações nesta etapa: a retirada de tráfego segue a disponibilidade da instância, sem retirar todas as réplicas por uma falha compartilhada. A saúde do banco permanece consultável pelo health agregado. O indicador SMTP fica desabilitado para evitar conexões de diagnóstico a uma integração opcional, inclusive quando o worker está desligado. Readiness UP não certifica contratos, acesso às integrações ou homologação ponta a ponta.

GET/HEAD bem-sucedidos exclusivamente nesses três paths não geram HTTP_REQUEST_STARTED/HTTP_REQUEST_COMPLETED. Respostas de erro e exceções continuam registradas pelo filtro, com restauração do MDC. Demais paths/métodos mantêm a observabilidade existente.

Antes da criação dos beans, `LocalProfileEnvironmentPostProcessor` recusa o profile `local` quando a detecção nativa do Spring Boot encontra as variáveis de serviços injetadas pelo Kubernetes no ambiente do processo (como `KUBERNETES_SERVICE_HOST` e `KUBERNETES_SERVICE_PORT`). A validação ocorre após a resolução de profiles, incluindo default, include e grupos. `spring.main.cloud-platform=none` não contorna essa guarda; uma variável arbitrária `ENVIRONMENT` não a ativa. Fora do Kubernetes, `local` continua permitido. As guardas financeiras permanecem inalteradas.

Referência: [probes do Spring Boot](https://docs.spring.io/spring-boot/4.0/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes). Manifests, isolamento de rede e deploy ficam para a próxima etapa; os endpoints de health não devem ser publicados indiscriminadamente pelo Gateway.

## Logs e correlação

ECS significa Elastic Common Schema. O serviço emite JSON ECS em stdout pelo Spring Boot (`logging.structured.format.console=ecs`), SLF4J 2 Fluent API e `addKeyValue`. Campos com pontos são organizados em objetos aninhados pelo formatter. `LOG_LEVEL` controla o nível geral, INFO por padrão. Domain/Application não possuem logging.

`HttpObservationFilter` registra HTTP_REQUEST_STARTED e HTTP_REQUEST_COMPLETED, inclusive em rejeições anteriores ao controller, com a exceção de health descrita acima. Aceita um único X-Correlation-Id UUID, ou gera outro se ausente/inválido/repetido. Mantém MDC `correlation.id` no fluxo e restaura o contexto em finally, sem header de resposta ou preenchimento manual de trace.id/span.id.

O evento final contém método, rota conhecida/template, status, duração em nanossegundos (`event.duration`), resultado e erro/etapa disponíveis. Antes da resolução MVC, a rota pode ser UNMAPPED. O histórico interno não atende à seleção de templates `/api/` e também permanece UNMAPPED. Não registra URI arbitrária/query/body.

MDC cobre Servlet síncrono, integrações e callbacks de commit na requisição. Não se propaga por headers/executores/reinícios; webhook é nova requisição. Workers se relacionam por IDs comerciais e event.id/notification.id. A correlação do envelope SQS é independente. Não há tracing distribuído configurado.

## Integrações e marcos

`IntegrationObservation` registra início/resultado da operação lógica, incluindo validação da resposta, por INTEGRATION_STARTED, INTEGRATION_COMPLETED ou INTEGRATION_FAILED. Inclui integração/operação, status recebido, duração, IDs conhecidos e classificação segura de falha, sem payload.

| integration.name | operation.name | Significado |
| --- | --- | --- |
| OS_SERVICE | QUOTATION_CONTEXT_GET | Contexto Budget |
| INVENTORY_SERVICE | PRICES_GET | Preços; método HTTP efetivo POST |
| OS_SERVICE | PAYMENT_CONTEXT_GET | Estado para checkout |
| MERCADO_PAGO | SELLER_IDENTITY_GET | Titular da credencial |
| MERCADO_PAGO | ORDER_CREATE | Criação de checkout |
| MERCADO_PAGO | ORDER_GET | Consulta financeira; duração inclui a validação prévia do titular |

TIMEOUT, CONNECTION_FAILED, HTTP_ERROR e categorias do adaptador distinguem falhas. Pré-condição recusada pode produzir evento sem I/O: início de integração não comprova requisição externa.

| event.code | O que comprova |
| --- | --- |
| BUDGET_CREATED / BUDGET_REVISION_CREATED | DRAFT persistido após commit |
| BUDGET_APPROVAL_REQUESTED / BUDGET_APPROVED / BUDGET_REJECTED | Transição e Outbox confirmadas |
| CHECKOUT_CREATION_RESERVED | Tentativa/reserva confirmadas |
| CHECKOUT_CREATED | Order/URL persistidas, sem registrar URL |
| CHECKOUT_REUSED | Retorno local sem criação externa |
| CHECKOUT_OUTCOME_UNKNOWN | Incerteza registrada sem liberar reserva |
| PAYMENT_ORDER_NOTIFICATION_OBSERVED | Situação processada, sem provar PAID por si |
| PAYMENT_CONFIRMED | PAID e evento confirmados juntos |
| BUDGET_NOTIFICATION_SENT | Aceite SMTP e confirmação local |
| BUDGET_EVENT_PUBLISHED | Aceite SQS e confirmação local, inclusive para Payment |

Códigos BUDGET_* do publicador foram preservados na reutilização por Payment. Marcos comerciais só ocorrem após commit; sucesso de worker, após confirmação local. `ObservationLog.safely` impede que RuntimeException de logging altere o resultado comercial.

## Diagnóstico de webhook

Localize HTTP_REQUEST_COMPLETED e correlation.id, depois PAYMENT_WEBHOOK_REJECTED com etapa/categoria. Após PAYMENT_WEBHOOK_VERIFIED acompanhe consulta, situação e PAYMENT_CONFIRMED/event.id quando houver quitação.

| Etapa/categoria | Interpretação |
| --- | --- |
| CONFIGURATION / WEBHOOK_DISABLED | Recepção desligada; 503 |
| SIGNATURE_HEADERS / MISSING_SIGNATURE, REPEATED_SIGNATURE, REPEATED_REQUEST_ID | Ausência/ambiguidade de headers |
| ORDER_ID / MISSING_ORDER_ID, REPEATED_ORDER_ID | data.id ausente/repetido |
| HMAC / INVALID_ORDER_ID, INVALID_REQUEST_ID, INVALID_SIGNATURE_FORMAT | Dados/estrutura inválidos |
| HMAC / SIGNATURE_MISMATCH | Comparação falhou; não identifica sozinho qual componente divergiu |
| ENVELOPE ou NOTIFICATION / INVALID_NOTIFICATION | Content-Type/corpo incoerente |
| FINANCIAL_LOOKUP | Verificação do vendedor/consulta falhou |
| WAITING_LOCAL_ASSOCIATION / ORDER_NOT_ASSOCIATED_LOCALLY | 503 vazio; sem vínculo, consulta ou sucesso de processamento |
| OBSERVED_INDETERMINATE | Evidência insuficiente para quitar |

PAYMENT_WEBHOOK_HMAC_STARTED, PAYMENT_WEBHOOK_HMAC_VERIFIED e PAYMENT_WEBHOOK_SIGNATURE_VERIFIED são DEBUG. O último inclui estratégia ORIGINAL/LOWERCASE_COMPATIBILITY, ID original, resultado e ambiente. PAYMENT_WEBHOOK_SIGNATURE_COMPATIBILITY_APPLIED é INFO no sucesso alternativo. HMAC válido não garante 200/PAID.

Não existem mais captura bruta, snapshots A/B ou flag de diagnóstico em runtime. A propriedade histórica `billing.payment.mercado-pago-webhook.signature-diagnostics-enabled` foi removida e o binding estrito a recusa. A [ADR 0013](../adr/0013-official-webhook-signature-validator.md) conserva o histórico sem autorizar o procedimento antigo.

## Proteção e instrumentação disponível

Logs não incluem tokens, e-mails, CPF/cartão, chaves idempotentes, assinatura/manifesto, credenciais, funcionário, URL de checkout ou payloads completos. `SafeExceptionDetails` preserva tipos/frames e omite mensagens externas; mensagens técnicas controladas podem aparecer. Filtros públicos ocultam conteúdo protegido do MVC. Access log Tomcat e request-details estão desligados; não habilitar dumps/wire logging para investigar assinatura.

Não há Actuator, Prometheus, Datadog, OTLP, tracer ou métricas de negócio configurados no POM/recursos. `event.duration` é atributo de log, não métrica exportada. Ingestão, alarmes, tracing e proteção dos logs de Gateway/ingress são dependências externas não comprovadas. Não há SLA/SLO definido neste repositório.

Testes em [infrastructure/observability](../../src/test/java/br/com/fiap/numberone/billing/infrastructure/observability), WebhookObservabilityTest e ITs de persistência exercitam MDC/ECS/sanitização e commit/rollback. [Operação](local-testing.md) e [pendências](../technical-debt.md).
