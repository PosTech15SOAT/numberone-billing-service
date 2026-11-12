# Contratos HTTP de pagamento

## API administrativa

Métodos implementados em `PaymentAdminController`:

| Método | Path | Entrada | Sucesso |
| --- | --- | --- | --- |
| POST | `/api/admin/ordens-servico/{serviceOrderId}/pagamentos/checkout` | UUID canônico da OS, único header `Idempotency-Key` UUID canônico e nenhum corpo | 200 ou 202, CheckoutResponse, Location e no-store |
| GET | `/api/admin/ordens-servico/{serviceOrderId}/pagamentos/checkout` | UUID da OS; sem chave idempotente requerida | 200, ChargeCheckoutResponse e no-store |

O POST recusa qualquer byte no corpo, inclusive `{}` e espaços. Chaves ausentes, repetidas ou combinadas por vírgula são 400. Não recebe valor, moeda, meio, orçamento ou status. O cliente deve conservar a mesma chave para repetir a operação.

Autenticação/autorização administrativa pertence ao Gateway/Authorizer ([ADR 0006](../adr/0006-centralized-authentication.md)). Billing não valida JWT nem exige `X-Authenticated-Subject` para checkout. O isolamento da entrada administrativa continua pendente de comprovação externa; o header de auditoria Budget não muda o resultado Payment.

### CheckoutResponse

| Campo | Tipo | Significado |
| --- | --- | --- |
| idCobranca | UUID string | Cobrança local |
| idTentativa | UUID string | Tentativa identificada pela chave do consumidor |
| idOrdemProvedor | string ou null | Order associada; não é ID do pagamento financeiro |
| urlCheckout | string ou null | URL hospedada validada e persistida |
| status | string | PENDING, UNKNOWN, PAID, FAILED, EXPIRED |
| situacao | string | Estado de apresentação conforme tabela abaixo |

| Estado local | HTTP POST | situacao |
| --- | --- | --- |
| PENDING com URL | 200 | DISPONIVEL |
| PENDING sem URL | 202 | EM_PROCESSAMENTO |
| UNKNOWN (com ou sem URL) | 202 | RESULTADO_DESCONHECIDO |
| PAID / FAILED / EXPIRED em replay | 200 | ENCERRADO |

Location aponta para a consulta do mesmo recurso. Não há 201 para a primeira chamada. 202 informa o estado local e não promete recuperação automática. Criar order não confirma pagamento; o fluxo de criação não produz FAILED/EXPIRED/PAID. O webhook pode produzir PAID conforme evidência e revalidação.

O GET retorna `idCobranca`, `idOrdemServico`, `valor` (número decimal), `moeda` (string), `status` da cobrança (OPEN/PAID) e `checkout` com a representação acima ou null se não há tentativas. Seleciona a tentativa mais recente por criação e UUID. Sem cobrança é 404. PENDING/UNKNOWN continuam 200 no GET. A consulta não acessa OS/Mercado Pago nem inicia/reconcilia checkout. Não há endpoint para listar o histórico de tentativas.

### Erros administrativos

Envelope `PaymentErrorResponse`: `status`, `codigo`, `mensagem`; `idCobranca` e `idTentativa` são omitidos quando nulos e incluídos no erro de resultado não confirmado localmente. Mensagens técnicas externas não são devolvidas. Erros controlados mantêm `Cache-Control: no-store`.

| HTTP | codigo | Condição |
| --- | --- | --- |
| 400 | ENTRADA_INVALIDA | UUID/chave/corpo inválido ou dados financeiros incompatíveis |
| 404 | OS_NAO_ENCONTRADA | Consulta remota de OS retorna 404 |
| 404 | COBRANCA_NAO_ENCONTRADA | GET sem cobrança local |
| 409 | OS_NAO_CONCLUIDA | OS não está COMPLETED |
| 409 | ORCAMENTO_APROVADO_INDISPONIVEL | Não existe exatamente um Budget aprovado da OS |
| 409 | CONFLITO_IDEMPOTENCIA | Chave/referência incompatível ou colisão de persistência |
| 409 | CONFLITO_ESTADO_PAGAMENTO | Tentativa bloqueante/quitada ou transição incompatível |
| 502 | INTEGRACAO_OS_INVALID_RESPONSE | Consulta de OS estruturalmente inválida ou redirect |
| 503 | INTEGRACAO_OS_DISABLED / INTEGRACAO_OS_UNAVAILABLE | Porta desabilitada ou dependência indisponível |
| 504 | INTEGRACAO_OS_TIMEOUT | Timeout de OS |
| 504 | CHECKOUT_TIMEOUT | Timeout de preparação antes da reserva |
| 503 | CHECKOUT_INDISPONIVEL | Comunicação/rate limit/falha de provedor na preparação |
| 503 | CHECKOUT_CONFIGURACAO_INDISPONIVEL | Integração desabilitada, autorização/vendedor inválido antes da reserva |
| 502 | CHECKOUT_RESPOSTA_INVALIDA | Resposta inválida na preparação |
| 503 | RESULTADO_CHECKOUT_DESCONHECIDO | Resultado/UNKNOWN não confirmado com segurança; inclui IDs para consulta |
| 500 | FALHA_TECNICA | Falha inesperada ou tentativa de I/O com transação ativa |

Falhas tipadas anteriores à reserva não criam cobrança/tentativa/UNKNOWN. Falhas tipadas do POST externo após a reserva tentam gravar UNKNOWN e normalmente retornam 202; essa distinção é do [fluxo](../flows/payment.md). Replay conhecido retorna antes das consultas externas, mas continua validando chave/orçamento/cobrança locais.

## Consulta externa da OS

Contrato consumido por `ServiceOrderPaymentRestGateway`, ainda pendente de confirmação e autenticação com OS Service:

```http
GET /internal/service-orders/{serviceOrderId}
Accept: application/json
```

Exemplo literal do mock, substituído deterministicamente com o UUID do cenário local:

```json
{
  "serviceOrderId":"00000000-0000-4000-8000-000000000101",
  "status":"COMPLETED",
  "updatedAt":"2026-11-10T03:00:00Z"
}
```

O mapper consome somente `serviceOrderId` e `status` textuais, exige identidade correspondente e status no formato `[A-Z][A-Z_]{0,63}`. O domínio exige exatamente `COMPLETED`; não traduz `FINALIZADA`. `updatedAt` e outros campos não participam da decisão. Resposta exige application/json e no máximo 16 KiB; sem redirects/retry. 404 → OS não encontrada; 408/timeout → 504; outros 4xx/5xx → indisponível; redirect/JSON inválido → 502.

| Configuração | Padrão e limite |
| --- | --- |
| PAYMENT_OS_LOCAL_UNAUTHENTICATED_ENABLED | false; opt-in somente com profile exclusivamente local |
| PAYMENT_OS_LOCAL_BASE_URL | vazio; HTTP/HTTPS somente localhost, 127.0.0.1 ou ::1, sem path adicional, credenciais, query ou fragmento |
| billing.payment.service-order.connect-timeout / read-timeout | 2s / 5s; entre 1ms e 1min |

O modo local não autentica serviço. Fora de exclusivamente local, habilitá-lo impede startup. Desabilitado, o fallback falha antes de reservar. Não há caminho de autenticação serviço a serviço para habilitação EKS. Configuração Budget e Payment da OS é separada. O mock versionado dessa rota retorna COMPLETED para o UUID recebido; não comprova conclusão real da OS.

A integração administrativa externa precisa aceitar o fluxo de checkout em português e a escolha de meio pelo cliente. Não há alias `/api/admin/payments` nem resposta de instruções PIX escolhidas pelo funcionário. A projeção/consumo financeiro na OS permanece dependência externa; não se presume equivalência com propostas anteriores.

## Webhook público

`POST /api/public/pagamentos/notificacoes/mercado-pago` usa autorização HMAC própria, independente da administrativa. Parâmetros, corpo, status e erros estão no [contrato Mercado Pago](mercado-pago.md#webhook-público), sem repetir aqui o envelope externo.

Fontes: [API Payment](../../src/main/java/br/com/fiap/numberone/billing/payment/api), [testes HTTP](../../src/test/java/br/com/fiap/numberone/billing/payment/api), [ServiceOrderPaymentRestGateway](../../src/main/java/br/com/fiap/numberone/billing/payment/infrastructure/rest/ServiceOrderPaymentRestGateway.java), [mock OS](../../docker/mockoon/os-service.json). Para concorrência, reserva e quitação, veja [pagamento](../flows/payment.md).
