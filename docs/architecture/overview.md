# Arquitetura do NumberOne Billing

O Billing é responsável por revisões de orçamento, decisões de aprovação, solicitações de e-mail, cobranças e tentativas de pagamento. OS Service mantém a ordem de serviço; Inventory Service fornece preços. O Billing conserva um snapshot comercial e publica fatos confirmados por uma Outbox PostgreSQL.

## Contexto — C4 nível 1

```mermaid
flowchart LR
    employee[Funcionário] -->|Comandos administrativos| auth[API Gateway e Lambda Authorizer]
    auth -->|HTTP - fronteira definida na ADR 0006| billing[NumberOne Billing]
    customer[Cliente] -->|HTTPS - decisão por token| billing
    customer -->|Checkout hospedado| mp[Mercado Pago]
    billing -->|HTTP - contexto da OS| os[OS Service]
    billing -->|HTTP - preços| inventory[Inventory Service]
    billing -->|SMTP - solicitação de aprovação| smtp[Servidor SMTP]
    billing -->|Eventos via SQS| os
    billing -->|HTTPS - criação e consulta de order| mp
    mp -->|Webhook assinado| billing
```

Gateway/Authorizer é a fronteira de autenticação administrativa decidida para a plataforma; seu provisionamento e isolamento não são comprovados por este repositório. As relações OS/Inventory representam os adaptadores implementados, exercitados com mocks. O consumo dos eventos na OS depende de implementação externa. Mercado Pago opera somente por habilitação explícita em `local`/`TEST`.

## Containers — C4 nível 2

```mermaid
flowchart TB
    admin[Cliente HTTP administrativo] --> gateway[API Gateway e Lambda Authorizer externos]
    gateway -->|HTTP| app
    browser[Navegador do cliente] -->|HTTP local ou HTTPS público - token| app
    subgraph boundary[Limite do Billing]
        app[Aplicação Java 25 / Spring Boot 4.0.5]
        db[(Database PostgreSQL do Billing)]
        app -->|JPA e JDBC - transações locais| db
    end
    app -->|RestClient - contexto e estado| os[OS Service]
    app -->|RestClient - consulta de preços| inventory[Inventory Service]
    app -->|AWS SDK - SendMessage| sqs[SQS Standard]
    sqs -.->|Consumo e Inbox externos pendentes| os
    sqs -.->|Redrive configurado localmente| dlq[DLQ]
    app -->|Spring Mail - SMTP com STARTTLS| smtp[Servidor SMTP]
    app -->|HTTPS - users/me| identity[Identidade do vendedor Mercado Livre]
    app -->|HTTPS - Orders API| mp[Mercado Pago]
    mp -->|HTTPS - webhook HMAC| app
```

Um container C4 representa uma aplicação ou armazenamento. Os workers executam dentro da aplicação Billing. O [Compose](../../docker-compose.yml) inicia PostgreSQL 17, LocalStack e dois servidores Mockoon; o Billing é iniciado no host. Não há manifesto de deployment EKS neste repositório. RDS é a decisão de destino da [ADR 0001](../adr/0001-billing-database.md), sem atestação de infraestrutura implantada.

## Componentes — C4 nível 3

```mermaid
flowchart TB
    api[Controllers e filtros HTTP] --> budget[UseCases de Budget]
    api --> payment[UseCases de Payment]
    budget --> bd[Domínio Budget]
    payment --> pd[Domínio Charge e PaymentAttempt]
    budget --> bp[Portas BudgetGateway / ServiceOrderGateway / InventoryGateway]
    payment --> pp[Portas ChargeGateway / CheckoutGateway / OrderLookupGateway]
    bp --> ba[Adaptadores JPA / JDBC / REST]
    pp --> pa[Adaptadores JPA / JDBC / Mercado Pago / OS]
    ba --> db[(PostgreSQL)]
    pa --> db
    db --> mail[BudgetNotificationProcessor]
    db --> outbox[OutboxPublisher]
    mail --> smtp[SMTP]
    outbox --> sqs[SQS]
```

As setas mostram a colaboração em execução; as interfaces das portas pertencem a `application`, e os adaptadores as implementam em `infrastructure`. O código se organiza primeiro por domínio (`budget`, `payment`), depois por `api`, `application`, `domain` e `infrastructure`. `domain` e `application` não dependem de Spring/JPA. A composição de beans e as transações ficam na infraestrutura. Mapeadores separam DTOs HTTP, agregados Java e entidades JPA.

Há acoplamento local explícito: `ChargeGatewayImpl` consulta `budget` via JDBC para validar o orçamento aprovado. Payment reutiliza o repositório/publicador de Outbox localizado no pacote de infraestrutura de Budget; `IntegrationEventOutboxEntry` está na infraestrutura compartilhada. Não há serviços separados para esses dois domínios.

## Consistência e limites de integração

| Limite | Garantia implementada |
| --- | --- |
| Budget | Contador e lock por OS, `@Version` JPA e validação de revisão vigente |
| Payment | Lock da raiz Charge, `@Version`, identidades únicas e uma tentativa bloqueante por cobrança |
| Commit local | Estado comercial e intenção de evento persistidos juntos; envio remoto ocorre depois |
| Checkout | Reserva permanente confirmada antes do POST; associação em outra transação |
| Notificação | Intenção durável separada da Outbox SQS; SMTP fora da transação |
| Distribuído | Sem transação distribuída, exactly-once, ordem global ou coordenador Saga implementado |

O Billing não consome SQS. Não há consumidor de `OSConcluida`, Inbox local ou compensação automática. Os TODOs de Saga apontam consumo externo da aprovação presencial, autenticação entre serviços, recuperação de checkout e tratamento de quitação histórica sem evento.

Veja [domínio](domain-model.md), [persistência](data-model.md), [fluxo de aprovação](../flows/budget-approval.md), [pagamento](../flows/payment.md), [Outbox](../flows/outbox.md) e [pendências](../technical-debt.md).
