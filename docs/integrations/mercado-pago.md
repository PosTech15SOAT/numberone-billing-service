# Integração Mercado Pago

## Capacidade e ambiente permitido

A implementação usa Checkout Pro via Orders API com `RestClient`: criação de order, consulta financeira e verificação do titular. O SDK `com.mercadopago:sdk-java:3.7.0` é usado somente pelo validador offline de assinatura, com transitivas excluídas. Não há cliente Point, entidade local de pagador, coleta de cartão ou endpoint de captura.

| Operação externa | Destino fixo | Pré-condição |
| --- | --- | --- |
| GET `/users/me` | `https://api.mercadolibre.com` | Integração habilitada, sem transação ativa |
| POST `/v1/orders` | `https://api.mercadopago.com` | Titular validado e reserva local confirmada |
| GET `/v1/orders/{order_id}` | `https://api.mercadopago.com` | Order local e titular validado nessa consulta |

Mercado Pago fica desabilitado por padrão. Habilitar exige exclusivamente profile `local`, ambiente exatamente `TEST`, token exportado em `MERCADO_PAGO_TEST_ACCESS_TOKEN` e vendedor esperado configurado. O valor vinculado da propriedade precisa coincidir com a variável real do processo; configurar somente em arquivo/argumento não satisfaz essa guarda. Webhook tem flag/ambiente/segredo próprios e também exige exclusivamente `local` quando habilitado.

`TEST-` e `APP_USR-` são formatos aceitos, não provas de ambiente. A origem na seção Teste e o ID do vendedor devem ser conferidos independentemente no painel. `/users/me` compara o titular, não certifica que a conta é TEST. A implementação não suporta habilitação EKS/produção; o nome do namespace não altera essas guardas. Não há override de endpoint, cache/fallback de identidade, redirects seguidos ou retry automático. Conexão/leitura padrão: 2s/5s. Startup valida configuração sem chamada autenticada.

## Identidade do vendedor

Antes de nova reserva, o verificador exige `/users/me` HTTP 200 com application/json, objeto JSON e `id` inteiro positivo Int64 igual ao ID esperado. Resposta tem limite de 64 KiB; campos duplicados e conteúdo extra após JSON são recusados. Não deduz o ID esperado do próprio retorno ou do token.

Falha impede criar cobrança/tentativa/reserva. O verificador retorna internamente a credencial validada: a operação de criação é de uso único e conserva exatamente esse token; cada GET financeiro também valida o titular e usa esse token. A [ADR 0012](../adr/0012-local-test-seller-verification.md) preserva a evolução do bloqueio inicial para o opt-in local.

## Criação de order

Headers: `Authorization: Bearer` com segredo externo, `X-Idempotency-Key` com UUID durável da tentativa, Content-Type/Accept application/json. `external_reference` é o UUID da tentativa. O mapper exige Charge OPEN, positiva, BRL e `amount.scale() <= 2`, tentativa PENDING pertencente à raiz e sem order/pagamento já conhecido.

Request determinístico derivado de `MercadoPagoOrderMapper.toRequest`, usando o valor e referência da [fixture sanitizada](../../src/test/resources/payment/checkout-pro-paid-installments.json):

```json
{
  "type":"online",
  "processing_mode":"manual",
  "total_amount":"10.00",
  "external_reference":"10000000-0000-4000-8000-000000000001",
  "items":[{"title":"Cobrança de ordem de serviço","quantity":1,"unit_price":"10.00"}]
}
```

O mapper não envia moeda, dados do pagador, cartão, meio, configuração de captura ou callbacks nesse DTO. `manual` é o `processing_mode` enviado à Orders API, sem representar captura posterior implementada no Billing.

A resposta aceita HTTP 2xx e no máximo 64 KiB; exige objeto JSON, `id`, `status` e `checkout_url` válidos. Campos presentes de moeda/valor/referência devem ser strings e coincidir com o modelo, mas são opcionais na criação. ID tem até 255 caracteres, não vazio, sem controles ou espaços nas extremidades. URL tem até 2048, HTTPS, host exato `www.mercadopago.com.br` ou `mercadopago.com.br`, porta padrão/443, sem credenciais/fragmento.

O resultado é `CheckoutOrder(providerOrderId, checkoutUrl, providerStatus)`: o status do provedor é informativo e não é persistido como quitação. Não se armazena `client_token`, access token ou cartão. A associação conserva a reserva e as identidades originais.

| HTTP/falha de criação | Categoria do adaptador |
| --- | --- |
| 400 / 422 | REQUEST_REJECTED |
| 401 / 403 | AUTHORIZATION |
| 408 / timeout local | TIMEOUT |
| 409 | IDEMPOTENCY_CONFLICT |
| 429 | RATE_LIMIT |
| 5xx | PROVIDER_FAILURE |
| Outros 4xx | OTHER_REJECTION |
| Redirect, resposta ausente/malformada/excessiva | INVALID_RESPONSE |
| Conexão/transporte | COMMUNICATION |

A indicação técnica de requisição recusada não prova ausência de operação anterior. Após reserva, o UseCase trata falhas tipadas conservadoramente como UNKNOWN; não confirma FAILED nem troca chave. Recuperação e status HTTP administrativos estão em [pagamento](../flows/payment.md) e [contrato HTTP](payment-http-contracts.md).

## Consulta de evidência financeira

GET usa ID original da order, Authorization Bearer e Accept application/json. Exige HTTP 200, application/json e corpo até 64 KiB. `MercadoPagoOrderDetailsMapper` recusa chaves duplicadas, JSON adicional, tipos inválidos e `live_mode=true` quando presente. Exige `id`, `type=online`, `processing_mode=manual`, `status`, `status_detail`, `total_amount`, `currency=BRL` e `external_reference` UUID textuais. `total_paid_amount` é opcional para parsing, necessário para aprovação.

`transactions`, quando presente, é objeto; payments/refunds/chargebacks, quando presentes, são arrays. Cada pagamento exige ID único, estados, `amount`; `paid_amount` é opcional no parsing. Valores são strings decimais de até 24 dígitos inteiros e duas casas; principal precisa ser positivo. Arrays de ajustes não vazios impedem aprovação. Campos extras de pagador/parcelas são ignorados pelo modelo financeiro, sem persistir payload bruto.

404 é NOT_FOUND de consulta; 401/403 AUTHORIZATION; 408 TIMEOUT; 429 RATE_LIMIT; 5xx PROVIDER_FAILURE; demais 4xx REQUEST_REJECTED; resposta inválida INVALID_RESPONSE. Sem integração habilitada, BLOCKED. A política de quitação integral e excedente coerente é detalhada no [fluxo](../flows/payment.md#evidência-necessária-à-quitação).

## Webhook público

```http
POST /api/public/pagamentos/notificacoes/mercado-pago
Content-Type: application/json
```

Query: um `data.id` obrigatório; `type` opcional, se presente deve ser `order`. Header `x-signature` obrigatório e único; `x-request-id` opcional e único. O ID assinado vem da query e deve coincidir exatamente com `data.id` do body. IDs permitidos: `[A-Za-z0-9_-]{1,255}`. Body limitado a 64 KiB, objeto com `type=order`, `live_mode=false`, `data.id` string; `api_version`, se presente, deve ser `v1`; `action`, se presente, deve seguir `order.[a-z_]+`.

O envelope não é evidência financeira. Depois de HMAC e validações, a aplicação consulta a order; não confia em status/valores enviados no webhook. O filtro oculta query, headers e body do MVC após extração, sem gravar conteúdo bruto.

### Assinatura

`MercadoPagoSignatureValidator` mantém guardas de formato antes de delegar ao SDK. `x-signature` tem até 2048 caracteres; exige `ts` com 1–20 dígitos e `v1` com 64 caracteres hexadecimais minúsculos. Partes duplicadas/ambíguas são recusadas; nomes adicionais devem corresponder a `v` seguido de dígitos. Não há janela temporal de expiração/antirreplay baseada em `ts`.

O manifesto inclui ID, request-id quando existente e timestamp, conforme a implementação do SDK utilizada pelos testes. Primeiro tenta `ORIGINAL`; apenas `SIGNATURE_MISMATCH`, com ID exclusivamente alfanumérico ASCII cuja caixa muda, permite segunda tentativa usando `Locale.ROOT` em minúsculas. Sucesso alternativo retorna `LOWERCASE_COMPATIBILITY`; hífen/underscore, ID numérico/já minúsculo, formato inválido e outros erros não recebem alternativa. O ID de negócio/consulta permanece original.

A compatibilidade autentica a forma canonicalizada e pode aceitar variantes de caixa com a mesma forma minúscula. Ela não prova equivalência de identidade entre orders; consulta autenticada, referência/valores e idempotência continuam obrigatórios. O histórico e o esclarecimento externo pendente estão na [ADR 0013](../adr/0013-official-webhook-signature-validator.md) e nos [débitos](../technical-debt.md#canonicalização-hmac).

### Respostas

| HTTP | Corpo/código | Condição |
| --- | --- | --- |
| 200 | Vazio | Observação processada; se APPROVED, confirmação local concluída antes da resposta |
| 503 | Vazio | Order autenticada ainda sem vínculo local; sem consulta externa nem recebimento durável |
| 400 | NOTIFICACAO_INVALIDA | Content-Type/envelope/body incoerente |
| 401 | NOTIFICACAO_NAO_AUTENTICADA | Assinatura/ID/header ausente, ambíguo ou inválido |
| 413 | NOTIFICACAO_INVALIDA | Body excede o limite |
| 502 | CONSULTA_ORDER_INVALID_RESPONSE | Consulta/evidência estrutural ou identidade inválida |
| 504 | CONSULTA_ORDER_TIMEOUT | Timeout ao validar vendedor/consultar |
| 503 | CONSULTA_ORDER_ seguido da categoria | BLOCKED, NOT_FOUND, AUTHORIZATION, COMMUNICATION, RATE_LIMIT, REQUEST_REJECTED, PROVIDER_FAILURE e demais categorias não mapeadas para 502/504 |
| 500 | FALHA_TECNICA_NOTIFICACAO | Falha técnica/commit/conflito financeiro não traduzido como consulta |

Erros JSON usam `PaymentErrorResponse` com `status`, `codigo`, `mensagem="Notificação não processada com segurança"`, sem IDs nulos. Respostas do filtro têm no-store. Integração financeira desabilitada não é 401: webhook desligado resulta em CONSULTA_ORDER_BLOCKED/503. Um 200 com situação não aprovada não altera Charge nem libera reserva.

Não há Inbox/deduplicação da entrega HTTP antes da consulta. Repetições autenticadas podem consultar novamente; lock/estado/Outbox evitam nova quitação do mesmo fato. A resposta 503 para order sem vínculo depende da reentrega do emissor e não garante recuperação. A política e o limite de retry do provedor não são garantias deste serviço.

Fontes: [configuração e adaptadores](../../src/main/java/br/com/fiap/numberone/billing/payment/infrastructure), [filtro](../../src/main/java/br/com/fiap/numberone/billing/payment/api/security/OrderWebhookRequestFilter.java), [testes de integração](../../src/test/java/br/com/fiap/numberone/billing/payment/infrastructure/persistence/OrderWebhookObservationIT.java), [procedimento local](../operations/local-testing.md). A disponibilidade desses adaptadores e testes não certifica a homologação da jornada remota.
