# NumberOne Billing Service

Serviço de orçamentos e pagamentos do NumberOne. Mantém revisões comerciais, aprovação presencial ou por link temporário, notificações SMTP, cobranças e tentativas de checkout. Publica fatos confirmados por Outbox PostgreSQL/SQS. OS Service continua responsável pela ordem de serviço; Inventory Service fornece preços.

## Stack e pré-requisitos

Java 25, Spring Boot 4.0.5, PostgreSQL 17, Flyway, Spring Data JPA/JDBC, Thymeleaf, Spring Mail e AWS SDK SQS. Checkout Pro/Orders API usa RestClient; SDK Mercado Pago 3.7.0 valida somente assinatura offline. Build pelo Maven Wrapper 3.9.14. Docker com Compose v2 é necessário para dependências locais e testes Testcontainers.

## Execução local

Na raiz, crie `.env` a partir do exemplo somente se ainda não existir e defina a senha PostgreSQL:

```bash
cp -n .env.example .env
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
unset AWS_SESSION_TOKEN
docker compose up -d --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

As credenciais fictícias acima são exclusivas do LocalStack. O profile local importa `.env`, mas o SDK AWS precisa das variáveis no processo. Compose inicia PostgreSQL (`5433`), LocalStack (`4566`), OS mock (`3001`) e Inventory mock (`3002`); Billing roda no host, porta `8080` por padrão. O volume PostgreSQL é persistente; LocalStack é descartável.

Configurações essenciais: `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, bases `OS_SERVICE_BASE_URL` e `INVENTORY_SERVICE_BASE_URL`. Sem profile local, configure também `DB_URL`; não há profile de produção versionado. Outbox é habilitada por padrão em local e desabilitada no comum. SMTP e Mercado Pago são opt-in. Detalhes de variáveis, health checks, mocks e diagnóstico em [execução local](docs/operations/local-testing.md).

Os manifests de homologação e o workflow manual ECR/EKS estão preparados em [Kubernetes e CI/CD](k8s/README.md), com contrato de configuração externa e dependências ainda não confirmadas. Essa estrutura mantém integrações financeiras e workers desabilitados.

Solicitar/reenviar aprovação exige `BUDGET_NOTIFICATION_ENCRYPTION_KEY` (32 bytes em Base64) e `BUDGET_APPROVAL_BASE_URL`, mesmo com SMTP desligado. Para entrega, configure `SMTP_*` e `BUDGET_NOTIFICATIONS_ENABLED=true`. O e-mail é texto simples; Thymeleaf renderiza a página pública de decisão.

Mercado Pago e webhook exigem exclusivamente profile local/TEST, flags, token no processo e vendedor esperado conferido independentemente. O transporte atual não pode ser habilitado no EKS. [Guardas e contratos](docs/integrations/mercado-pago.md).

## Build e testes

```bash
./mvnw test
./mvnw verify
./mvnw package -DskipTests
```

Surefire executa `*Test`; Failsafe executa `*IT` durante verify. ITs requerem Docker/PostgreSQL Testcontainers; alguns testes HTTP também abrem sockets locais. O POM configura agentes Mockito/JaCoCo e gera cobertura consolidada em `target/site/jacoco-merged/`. [Qualidade e Sonar](docs/operations/quality.md).

## Endpoints principais

| Operação | Método e path |
| --- | --- |
| Criar revisão | POST `/api/admin/ordens-servico/{serviceOrderId}/orcamentos` |
| Consultar orçamento | GET `/api/admin/orcamentos-ordem-servico/{id}` |
| Solicitar aprovação | PATCH `/api/admin/orcamentos-ordem-servico/{id}/solicitar-aprovacao` |
| Registrar aprovação presencial | POST `/api/admin/orcamentos-ordem-servico/{id}/registrar-aprovacao` |
| Consultar/gerar checkout | GET/POST `/api/admin/ordens-servico/{serviceOrderId}/pagamentos/checkout` |
| Página de decisão | GET `/api/public/orcamentos-ordem-servico/decisao` |
| Webhook | POST `/api/public/pagamentos/notificacoes/mercado-pago` |

Todos os endpoints, DTOs, erros, autenticação, histórico, decisões e reenvio estão nos contratos de [Budget](docs/integrations/budget-http-contracts.md) e [Payment](docs/integrations/payment-http-contracts.md). Não há Swagger/OpenAPI configurado.

## Documentação

| Tema | Documentos |
| --- | --- |
| Arquitetura | [Visões C4 e limites](docs/architecture/overview.md), [domínio](docs/architecture/domain-model.md), [glossário](docs/architecture/glossary.md) |
| Persistência | [ERD, dicionário e constraints](docs/architecture/data-model.md) |
| Fluxos | [Orçamento e e-mail](docs/flows/budget-approval.md), [pagamento](docs/flows/payment.md), [Outbox](docs/flows/outbox.md) |
| Integrações | [HTTP Budget](docs/integrations/budget-http-contracts.md), [HTTP Payment](docs/integrations/payment-http-contracts.md), [eventos](docs/integrations/events.md), [Mercado Pago](docs/integrations/mercado-pago.md) |
| Operação | [Execução/testes locais](docs/operations/local-testing.md), [observabilidade](docs/operations/observability.md), [qualidade](docs/operations/quality.md) |
| Pendências | [Débitos técnicos e contratos externos](docs/technical-debt.md) |

## Decisões arquiteturais

1. [Banco PostgreSQL](docs/adr/0001-billing-database.md)
2. [Contador transacional de revisões](docs/adr/0002-budget-version-counter.md)
3. [Concorrência de Budget](docs/adr/0003-budget-optimistic-locking.md)
4. [Transactional Outbox](docs/adr/0004-transactional-outbox.md)
5. [Mensageria SQS](docs/adr/0005-message-broker-selection.md)
6. [Autenticação centralizada](docs/adr/0006-centralized-authentication.md)
7. [Autorizações e notificações](docs/adr/0007-budget-approval-notifications.md)
8. [Agregado financeiro e concorrência](docs/adr/0008-payment-aggregate-and-local-concurrency.md)
9. [Checkout Pro TEST](docs/adr/0009-checkout-pro-orders-test-only.md)
10. [Reserva durável de checkout](docs/adr/0010-durable-checkout-reservation.md)
11. [Quitação e Outbox](docs/adr/0011-integral-payment-confirmation-and-outbox.md)
12. [Verificação de vendedor local](docs/adr/0012-local-test-seller-verification.md)
13. [Validação de assinatura](docs/adr/0013-official-webhook-signature-validator.md)

[Template de ADR](docs/adr/TEMPLATE.md). ADRs preservam decisões e sua evolução; os documentos temáticos descrevem o comportamento atual.

## Limites operacionais

Autenticação administrativa pertence a API Gateway/Lambda Authorizer; Billing não valida JWT nem protege acesso administrativo direto. Isolamento de rede e procedência do contexto de auditoria precisam ser comprovados na plataforma. Mocks e testes não validam contratos dos serviços reais.

PUBLISHED significa aceite SQS confirmado localmente; SENT da notificação significa aceite SMTP. Ambos podem duplicar em falhas de confirmação. O Billing não consome SQS nem implementa coordenador Saga. PENDING/UNKNOWN com reserva podem permanecer bloqueados sem recuperação automática; 202 não promete conclusão futura. Não há métricas exportadas/tracing/Actuator configurados. Esses limites e as dependências externas estão no [registro de débitos](docs/technical-debt.md).

No MVP, as APIs administrativas e internas do Billing não possuem autenticação ou autorização HTTP. A autoria presencial usa UUID declarado em `X-Authenticated-Subject`. OS e Inventory são acessados por ClusterIP/DNS interno sem Bearer; acesso direto permite operações e autenticação/autorização futura permanece débito técnico. Consulte os contratos de integração para os limites e validações.

## Preparação integrada de deploy

[Prontidão EKS e roteiro dos três serviços](docs/deployment/eks-readiness/README.md) e
[Kind integrado versionado neste repositório](dev/kind/README.md).
