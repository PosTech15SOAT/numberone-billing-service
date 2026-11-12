# Execução e testes locais

## Configuração

Pré-requisitos: JDK 25, Docker com Compose v2 e Maven Wrapper incluído. Wrapper usa Maven 3.9.14; versões de dependências estão no [POM](../../pom.xml). Mockoon Desktop é opcional. Na raiz, copie o exemplo somente se `.env` ainda não existir:

```bash
cp -n .env.example .env
```

Defina senha PostgreSQL local e revise URLs/portas. O profile `local` importa `.env` opcionalmente como propriedades Spring, sem exportar variáveis para o SDK AWS. Forneça ao processo as credenciais fictícias exclusivas do emulador:

```bash
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
unset AWS_SESSION_TOKEN
```

Não é necessário carregar todo o arquivo com `source`. Nunca use essas credenciais contra AWS real.

| Configuração | Papel / padrão |
| --- | --- |
| DB_NAME / DB_USERNAME / DB_PASSWORD | Banco, usuário e senha locais |
| DB_PORT | 5433 no host |
| DB_URL | Opcional em local; monta JDBC com DB_PORT/DB_NAME se ausente; obrigatório no profile comum |
| OS_SERVICE_BASE_URL / INVENTORY_SERVICE_BASE_URL | URLs Budget dos mocks: localhost:3001 / localhost:3002 no exemplo |
| AWS_REGION / LOCALSTACK_PORT | us-east-1 / 4566 |
| OUTBOX_ENABLED | false comum; true em local |
| SQS_ENDPOINT / SQS_QUEUE_URL | Ausentes no comum; destinos localhost em local |
| BUDGET_NOTIFICATIONS_ENABLED | false, inclusive local; controla somente o worker SMTP |
| LOG_LEVEL | INFO |
| GATEWAY_EMPLOYEE_SUBJECT_HEADER | X-Authenticated-Subject; auditoria presencial |

Sem profile local, configure DB_URL, usuário/senha e bases OS/Inventory explicitamente. Não existe profile de produção versionado ou deployment comprovado. Variáveis do processo prevalecem sobre o arquivo importado; propriedades JVM/argumentos Spring podem sobrescrever propriedades. Para defaults sem variável dedicada, prefira argumento com o nome exato, como `--billing.outbox.batch-size=5`; não invente alias `OUTBOX_BATCH_SIZE`. Nomes completos na [configuração fonte](../../src/main/resources/application.properties).

## Infraestrutura e aplicação

```bash
docker compose up -d --wait
docker compose ps
docker compose logs postgres localstack mockoon-os mockoon-inventory
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Compose inicia dependências, não o Billing. Publica PostgreSQL 17 em 5433, LocalStack 4.14.0 em 4566, Mockoon CLI 9.9.0 para OS em 3001 e Inventory em 3002. Portas dos mocks são fixas no Compose; DB/LocalStack usam variáveis. API Spring usa 8080 por padrão. Health checks conferem banco, inicialização das filas e respostas dos mocks; não há Actuator/endpoint de saúde Billing configurado.

O volume `billing_postgres_data` conserva os dados. LocalStack não possui volume persistente: recriar seu container pode remover mensagens já marcadas PUBLISHED no PostgreSQL. Não usar `down -v` como inicialização. Para encerrar conservando o volume PostgreSQL:

```bash
docker compose down
```

## Mocks e orçamento

[OS](../../docker/mockoon/os-service.json) e [Inventory](../../docker/mockoon/inventory-service.json) simulam os contratos consumidos pelos adaptadores, sem comprovar aceitação externa. São montados somente para leitura; Mockoon Desktop não precisa ficar aberto.

| Final do UUID de OS `00000000-0000-4000-8000-000000000…` | Cenário |
| --- | --- |
| 101 | Serviços/insumos elegíveis e item CANCELLED; automático 255.75 |
| 102 | Lista vazia; automático zero |
| 103 | Sem e-mail; criação/presencial possíveis, envio recusado |
| 104 | HTTP 409 de inelegibilidade |
| 105 | Status de item ausente |
| 106 | items nulo |
| 107 | Timeout OS |
| 108 | OS REJECTED elegível |
| 109 | Preço necessário ausente |
| 110 | Inventory indisponível |
| 111 | Resposta Inventory inválida |
| 112 | Timeout Inventory |
| Outro UUID | Contexto de orçamento 404 |

Cenário 101: `150.00 + 2 × 25.50 + 40.00 + 14.75 = 255.75`; item cancelado não entra. O mock Payment `GET /internal/service-orders/{id}` retorna COMPLETED para o ID recebido, sem simular conclusão real da OS.

```bash
curl -i -X POST \
  http://localhost:8080/api/admin/ordens-servico/00000000-0000-4000-8000-000000000101/orcamentos \
  -H 'Content-Type: application/json' -d '{}'
```

A resposta fornece UUID e Location reais. Use esse ID nos [comandos Budget](../integrations/budget-http-contracts.md); Budget não tem ID fixo de criação. Para SMTP real, configure `MOCK_CUSTOMER_EMAIL` com caixa de teste controlada, pois o domínio reservado do exemplo não comprova entrega.

## Autorização e SMTP

Solicitar/reenviar aprovação exige chave/URL mesmo com worker desligado. Gere 32 bytes Base64 e configure `BUDGET_NOTIFICATION_ENCRYPTION_KEY` sem versionar o valor:

```bash
openssl rand -base64 32
```

Use `BUDGET_APPROVAL_BASE_URL=http://localhost:8080/api/public/orcamentos-ordem-servico/decisao`. Outros hosts exigem HTTPS; sem query, fragmento ou credenciais. Alterar a URL não reescreve links cifrados antigos: reenvio cria novo destino. A chave deve ser estável e igual entre instâncias com pendências; não há keyring/rotação automática e hash não recupera token se a chave for perdida.

Para processar e-mails, configure SMTP_HOST, SMTP_PORT (587), SMTP_USERNAME, SMTP_PASSWORD e SMTP_FROM, depois habilite BUDGET_NOTIFICATIONS_ENABLED. O exemplo aponta para Brevo; o adaptador não fixa provedor. Exige autenticação, STARTTLS e verificação TLS. Worker habilitado valida credenciais/remetente/chave/URL ao iniciar, sem testar login/envio.

| Propriedade | Default |
| --- | --- |
| billing.notifications.authorization-ttl / resend-interval | 48h / 30s |
| billing.notifications.poll-interval / batch-size / lease-duration | 5s / 1 / 120s |
| billing.notifications.max-attempts | 5 claims |
| billing.notifications.initial-backoff / max-backoff | 30s / 30m |
| spring.mail.properties.mail.smtp.connectiontimeout / timeout / writetimeout | 5000ms cada |

SMTP debug é false. Aceite não comprova entrega/leitura. [Fluxo e falhas](../flows/budget-approval.md).

## SQS e PostgreSQL

```bash
docker compose exec localstack awslocal sqs list-queues
docker compose exec localstack awslocal sqs receive-message \
  --queue-url http://localhost:4566/queue/us-east-1/000000000000/numberone-billing-events \
  --max-number-of-messages 10
```

Ajuste região/porta se alteradas. `receive-message` aplica visibilidade, não remove mensagens nem representa consumidor OS. O script cria fila Standard/DLQ com redrive 5 e visibilidade 30s, sem provisionar AWS.

Abra psql usando as variáveis existentes no container, sem senha em argumento:

```bash
docker compose exec postgres sh -c 'exec psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

Consultas de diagnóstico sem conteúdo protegido:

```sql
SELECT event_id, event_type, status, attempt_count, next_attempt_at,
       published_at, last_error, lease_until
FROM integration_event_outbox ORDER BY created_at;
SELECT notification_id, budget_id, status, attempt_count,
       last_attempt_at, next_attempt_at, last_error
FROM budget_email_notification ORDER BY created_at;
SELECT charge_id, service_order_id, status, updated_at FROM charge;
SELECT attempt_id, charge_id, status, provider_order_id, provider_payment_id,
       checkout_creation_claimed_at FROM payment_attempt;
```

Não compartilhar consultas de conteúdo protegido, tokens ou payloads completos. Não existe recuperação automática de NEEDS_REVIEW/UNKNOWN. [Outbox](../flows/outbox.md).

| Propriedade Outbox | Default |
| --- | --- |
| billing.outbox.poll-interval / batch-size / lease-duration | 1s / 10 / 60s |
| billing.outbox.max-attempts / initial-backoff / max-backoff | 5 / 5s / 5m |
| billing.outbox.sqs.connection-timeout / read-timeout / api-call-timeout | 1s / 3s / 5s |
| spring.task.scheduling.shutdown.await-termination-period | 60s |

## Mercado Pago TEST manual

Pré-condições: token obtido na seção Teste, vendedor esperado conferido independentemente, comprador de teste distinto, Budget local aprovado e OS COMPLETED no mock. Isso descreve execução disponível, sem certificar homologação remota. A consulta OS Payment tem opt-in separado:

```bash
export PAYMENT_OS_LOCAL_UNAUTHENTICATED_ENABLED=true
export PAYMENT_OS_LOCAL_BASE_URL=http://127.0.0.1:3001
```

Para Mercado Pago, leia token sem exibir/gravar no histórico:

```bash
set +x
export MERCADO_PAGO_ENABLED=true
export MERCADO_PAGO_ENVIRONMENT=TEST
read -rp 'ID do vendedor conferido no painel Teste: ' MERCADO_PAGO_EXPECTED_TEST_SELLER_ID
export MERCADO_PAGO_EXPECTED_TEST_SELLER_ID
read -rsp 'Access Token da seção Teste: ' MERCADO_PAGO_TEST_ACCESS_TOKEN
printf '\n'
export MERCADO_PAGO_TEST_ACCESS_TOKEN
```

Reinicie Billing exclusivamente com profile local. Startup não valida a credencial remotamente. Solicite [checkout](../integrations/payment-http-contracts.md) sem corpo, conserve a chave UUID escolhida e pague no ambiente TEST. Validar provedor isoladamente no Insomnia não comprova persistência do Billing; não vincule order avulsa artificialmente ao banco.

Webhook exige MERCADO_PAGO_WEBHOOK_ENABLED=true, MERCADO_PAGO_WEBHOOK_ENVIRONMENT=TEST e MERCADO_PAGO_WEBHOOK_SECRET externo, distinto do token. Leia o segredo com `read -rsp`, exporte e reinicie. Sem recepção, GET administrativo não consulta provedor/confirma pagamento por conta própria.

Entrega remota exige origem HTTPS encaminhando o [webhook](../integrations/mercado-pago.md#webhook-público). Cloudflare Tunnel foi considerado transporte temporário, sem configuração versionada de isolamento. Um túnel para a porta inteira pode expor `/api/admin/**` e `/internal/**`; antes de usar, configure/verifique encaminhamento restrito à rota pública necessária. O Billing não autentica acesso administrativo direto; não há comando de exposição irrestrita neste guia.

No painel TEST, configure URL HTTPS/notificações de order. A entrega deve preservar query `data.id`, assinatura e corpo; simulação sem vínculo local pode retornar 503. Verifique assinatura, consulta, PAID e PagamentoConfirmado na Outbox sem capturar segredos; PUBLISHED comprova só aceite SQS. Ao terminar, interrompa aplicação/transporte e remova segredos do shell, preservando identidades/reservas do banco:

```bash
unset MERCADO_PAGO_TEST_ACCESS_TOKEN MERCADO_PAGO_WEBHOOK_SECRET
export MERCADO_PAGO_ENABLED=false
export MERCADO_PAGO_WEBHOOK_ENABLED=false
```

## Build e testes

```bash
./mvnw test
./mvnw verify
./mvnw package -DskipTests
```

Surefire seleciona `*Test`; Failsafe executa `*IT` em integration-test/verify. Parte dos testes HTTP abre sockets loopback; ITs usam PostgreSQL 17/Testcontainers e Docker. Automação não usa credenciais reais SMTP/MP/AWS. Agentes Mockito/JaCoCo já estão no POM; não copiar caminhos pessoais de agentes. `verify` gera cobertura em `target/site/jacoco-merged/`.

Seleção: `./mvnw -Dtest=BudgetCompositionTest,ChargeTest test` ou `./mvnw -Dit.test=PaymentConfirmationIT verify` (o segundo ainda passa por Surefire). `-o` requer dependências em cache. [Observabilidade](observability.md), [qualidade](quality.md), [débitos](../technical-debt.md).
