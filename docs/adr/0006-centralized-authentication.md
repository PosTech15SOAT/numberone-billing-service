# ADR 0006: Autenticação e autorização centralizadas no API Gateway

**Status:** Histórica — premissas de autenticação externas não se aplicam ao MVP atual

> A decisão atual do MVP é não exigir autenticação ou autorização HTTP nos serviços. O acesso direto permite operações; autenticação e autorização futuras são débito técnico. A autoria presencial é um UUID declarado. Consulte os contratos HTTP atuais para a configuração vigente.

**Data:** 2026-11-10

## Contexto

A autenticação e a autorização administrativa pertencem ao API Gateway e à Lambda Authorizer. O Billing aplica as regras comerciais de Budget e Payment. Exigir um header de identidade para liberar checkout duplicava a responsabilidade da entrada e impedia o fluxo local sem acrescentar autenticação efetiva.

Budget tem uma necessidade distinta: o registro presencial da aprovação grava `approvedByEmployeeId`. O domínio e a restrição `ck_budget_approval_audit` da V6 exigem um identificador não vazio para origem `IN_PERSON`. Esse dado de auditoria continua necessário e não representa uma autorização administrativa.

## Decisão

Centralizar exclusivamente na infraestrutura a autenticação e a autorização administrativa. O Billing não autentica funcionários, não valida JWT e não possui interceptor, filtro ou verificação equivalente de identidade para autorizar acesso administrativo. Payment não depende de `X-Authenticated-Subject`, incluindo POST e GET de checkout.

| Componente | Responsabilidade |
| --- | --- |
| Lambda Authorizer | Autenticar e verificar as permissões administrativas. |
| API Gateway | Aplicar a autorização de cada rota; fornecer contexto de auditoria quando necessário, removendo ou sobrescrevendo valores enviados pelo cliente. |
| Infraestrutura | Impedir acesso administrativo direto ao Billing que contorne o Gateway na AWS. |
| Billing | Validar regras comerciais e persistir resultados, auditoria necessária e Outbox. |

`X-Authenticated-Subject`, configurável por `GATEWAY_EMPLOYEE_SUBJECT_HEADER`, é somente contexto de auditoria para `POST /api/admin/orcamentos-ordem-servico/{id}/registrar-aprovacao`. O adaptador existente lê o identificador, sem atestar identidade, permissões ou procedência. A validação de integridade do contexto (um valor não vazio, até 255 caracteres, sem controles ou espaços nas extremidades) evita auditoria ambígua ou inválida. O comando mantém o erro 503 e o envelope existentes se esse contexto estiver indisponível; não persiste uma aprovação presencial incompleta. Essa exigência de dados pertence exclusivamente ao registro de auditoria, não a uma barreira de autorização dos endpoints.

A flag `GATEWAY_IDENTITY_ENABLED` foi removida. Não existe modo de autenticação simulada nem funcionário padrão local. O contexto de auditoria fornecido em uma chamada local não comprova autenticação. Não alterar o domínio, os registros históricos, as migrations ou as restrições da V6. A origem `REMOTE` e a jornada pública de autorizações temporárias permanecem preservadas.

Chamadas entre serviços têm política própria. O contexto de funcionário não é encaminhado ao OS Service. A decisão não habilita consulta de estado da OS no EKS nem muda seu modo local explícito e loopback. A assinatura HMAC do webhook, as guardas Mercado Pago TEST e as autorizações temporárias de Budget permanecem independentes da autenticação administrativa.

## Alternativas consideradas

- **Exigir header de identidade no checkout:** descartado; presença e formato não autenticam o cliente e não atendem finalidade comercial em Payment.
- **Autenticação no Billing ou funcionário padrão local:** descartados; duplicariam a política da plataforma ou inventariam contexto de auditoria.
- **Remover a auditoria presencial:** descartado; conflita com o domínio e a V6, que permanecem preservados.

## Consequências

### Positivas

- Checkout deixa de depender de identidade administrativa no Billing.
- Uma única fronteira autentica e autoriza os funcionários.
- Budget mantém auditoria presencial, regras comerciais, transação e OrcamentoAprovado.

### Negativas e trade-offs

- O backend não protege rotas administrativas contra acesso direto: o isolamento e o caminho obrigatório pelo Gateway devem ser comprovados na AWS.
- Cloudflare Tunnel local deve encaminhar somente as rotas públicas necessárias, sem expor `/api/admin/**`. Bind loopback isoladamente não impede encaminhamento pelo túnel.
- Os testes locais não comprovam autenticação, autorização ou isolamento da plataforma. Essas pendências estão no registro de débitos técnicos, na seção de isolamento da entrada.

## Referências

- [Contratos HTTP de Budget](../integrations/budget-http-contracts.md).
- [Contratos HTTP de Payment](../integrations/payment-http-contracts.md).
- [Pendências de integração](../technical-debt.md).
- [Débitos técnicos](../technical-debt.md).
- [Migration V6](../../src/main/resources/db/migration/V6__add_budget_approval_audit.sql).
