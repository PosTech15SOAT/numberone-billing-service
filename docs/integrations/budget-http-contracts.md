# Contratos HTTP de orçamento

Contratos efetivos de `BudgetAdminController`, `BudgetPublicController` e `BudgetIntegrationController`. Paths e nomes JSON são preservados. A [jornada de aprovação](../flows/budget-approval.md) contém as regras transacionais; este documento define o transporte.

## Rotas

| Método | Path | Entrada | Sucesso |
| --- | --- | --- | --- |
| POST | `/api/admin/ordens-servico/{serviceOrderId}/orcamentos` | UUID da OS; JSON obrigatório com `valorProposto` opcional | 201, BudgetResponse e Location da consulta individual |
| GET | `/api/admin/orcamentos-ordem-servico/{id}` | UUID do orçamento | 200, BudgetDetailsResponse |
| PATCH | `/api/admin/orcamentos-ordem-servico/{id}/solicitar-aprovacao` | UUID; sem corpo previsto | 200, BudgetResponse |
| POST | `/api/admin/orcamentos-ordem-servico/{id}/reenviar-aprovacao` | UUID; sem corpo previsto | 200, BudgetResponse |
| PATCH | `/api/admin/orcamentos-ordem-servico/{id}/aprovar` | UUID; sem corpo previsto | 200, BudgetResponse |
| PATCH | `/api/admin/orcamentos-ordem-servico/{id}/rejeitar` | UUID; sem corpo previsto | 200, BudgetResponse |
| POST | `/api/admin/orcamentos-ordem-servico/{id}/registrar-aprovacao` | UUID e contexto de auditoria | 200, BudgetResponse |
| GET | `/internal/ordens-servico/{serviceOrderId}/orcamentos` | UUID da OS | 200, BudgetHistoryResponse |
| GET | `/api/public/orcamentos-ordem-servico/decisao` | Query `token` | 200, HTML de consulta |
| POST | `/api/public/orcamentos-ordem-servico/aprovar` | Formulário `token` e `confirmacao=sim` | 200, HTML de confirmação |
| POST | `/api/public/orcamentos-ordem-servico/rejeitar` | Mesmo formulário | 200, HTML de confirmação |

Nos comandos sem DTO, o controller não lê um corpo; isso não implementa a recusa explícita de bytes usada em Payment. Não há endpoints de edição/cancelamento ou OpenAPI/Swagger configurado no POM atual.

Autenticação e autorização administrativas pertencem a Gateway/Authorizer ([ADR 0006](../adr/0006-centralized-authentication.md)); o Billing não valida JWT. `X-Authenticated-Subject` (nome configurável) é usado somente na auditoria presencial: um único identificador não vazio, até 255 caracteres, sem controles ou espaços nas extremidades. Ausência/contexto inválido retorna 503; o header não autentica o cliente. A procedência do header e o isolamento de rotas administrativas/internas dependem da plataforma, ainda não comprovados aqui. Rotas públicas de decisão usam o token temporário.

## Criação e representações

Corpos extraídos de `BudgetCreationHttpTest`:

```json
{"valorProposto":250.00}
```

```json
{}
```

Objeto vazio ou `valorProposto: null` solicita cálculo automático. Corpo ausente/malformado é 400. Manual deve ser positivo e exato em centavos: `1.23000` é aceito sem normalizar, `1.2301` é recusado antes de consultar serviços. Fração real de centavo nos preços/serviços do automático gera 422. Budget não tem moeda global. Lista de itens explicitamente vazia produz zero; não se inventa valor mínimo para cálculo automático.

Location: `/api/admin/orcamentos-ordem-servico/{id}`, com a origem produzida a partir da requisição.

`BudgetResponse` tem exatamente os nove campos abaixo; os comandos não incluem `notificacao`.

| Campo JSON | Tipo | Significado/nulidade |
| --- | --- | --- |
| id | UUID string | Identidade da revisão |
| idOrdemServico | UUID string | OS referenciada |
| valorProposto | número decimal | Valor comercial proposto |
| valorAprovado | número decimal ou null | Valor integral após aprovação |
| status | string | RASCUNHO, ENVIADO, APROVADO, REJEITADO, CANCELADO |
| enviadoEm | data-hora sem offset ou null | Envio comercial |
| aprovadoEm | data-hora sem offset ou null | Aprovação |
| created_at | data-hora sem offset | Criação |
| updated_at | data-hora sem offset ou null | Atualização; histórico pode conter null |

Versão comercial, snapshot, versão JPA, origem/funcionário, token e destinatário não são expostos nesse DTO.

O GET individual retorna esses campos na raiz, acrescidos de `notificacao`, nulo quando não existe solicitação. O resumo da última solicitação contém `status` (PENDENTE/ENVIADA/FALHA/CANCELADA), `tentativas` inteiro, `ultimaTentativaEm` e `enviadoEm` com offset ou null, e `podeReenviar` booleano. O resumo não acumula tentativas de solicitações anteriores. A flag combina SENT vigente e intervalo decorrido; é informativa, sem garantir e-mail disponível ou sucesso do próximo comando.

O histórico retorna `idOrdemServico`, `orcamentos` em ordem crescente de versão e `idOrcamentoReferencia` UUID/null. Cada revisão contém `id`, `versao`, `valorProposto`, `valorAprovado`, `status`, `enviadoEm`, `aprovadoEm`. A referência é a maior versão entre SENT/APPROVED/REJECTED na lista lida; só DRAFTs resultam em referência nula. Lista vazia é 200 e não comprova inexistência da OS, pois a consulta só lê o Billing. Não expõe snapshot, notificação ou versão técnica. Rota, projeção e autenticação entre serviços aguardam confirmação externa.

## Regras dos comandos e erros JSON

Envio exige DRAFT, OS elegível e e-mail. Reenvio exige SENT vigente e intervalo mínimo; não gera evento de envio nem muda versão/datas comerciais. Decisões legadas exigem SENT vigente. Presencial admite DRAFT elegível ou SENT vigente, com auditoria obrigatória, e pode gerar aprovação sem evento de envio anterior. Decisão pública consome sua autorização. Regras completas no [fluxo](../flows/budget-approval.md).

Envelope de erro administrativo/interno: `status` inteiro, `message` string e `errors` lista de strings. Sem erro de validação de campos, a lista é vazia. Não é o envelope Payment.

| HTTP | Condição |
| --- | --- |
| 400 | UUID, JSON/validação ou dado de orçamento inválido |
| 404 | Budget ou OS não encontrado |
| 409 | OS inelegível na criação, revisão obsoleta, aprovação impeditiva, conflito otimista ou reenvio antes do intervalo |
| 422 | Estado incompatível, cálculo inviável, e-mail ausente ou OS inelegível na validação de envio/presencial |
| 502 | Resposta externa inválida |
| 503 | Integração indisponível, contexto de auditoria ausente ou notificação não preparável |
| 504 | Timeout de integração |
| 500 | Falha inesperada; mensagem controlada |

Uma segunda decisão normalmente encontra o estado já alterado e retorna 422. Revisão obsoleta ou conflito de versão é 409. HTTP 409 recebido diretamente do adaptador OS é traduzido para inelegibilidade (409); não equivale à guarda de status no contexto (422 no envio).

## Jornada HTML pública

GET exige um único token no formato Base64 URL de 43 caracteres; consulta sem consumir. POST exige `application/x-www-form-urlencoded`, sem query string, com único token e única `confirmacao=sim`. O filtro converte entradas inadequadas em dados inválidos; ausência de confirmação é verificada antes da consulta do token. Não há decisão por GET nem redirecionamento com a credencial.

| HTTP | Motivo |
| --- | --- |
| 400 | Confirmação ausente/incorreta |
| 404 | Token inválido ou não encontrado |
| 410 | Autorização expirada ou revogada |
| 409 | Consumida, orçamento decidido, revisão obsoleta ou conflito otimista |
| 500 | Falha inesperada |

Sucesso/erro retornam HTML UTF-8, com no-store/private, no-referrer, nosniff, DENY e CSP restritiva. O resultado não reexibe token ou dados protegidos. `GET /css/budget.css` é recurso estático usado pelas páginas, sem autenticação do Billing.

## Serviços externos usados por Budget

Os contratos abaixo descrevem o que os adaptadores consomem; mocks não comprovam aceitação pelos serviços reais. Nenhuma autenticação serviço a serviço está configurada nesses clientes. Os nomes internos existentes permanecem em inglês.

| Operação | Request | Resposta consumida |
| --- | --- | --- |
| GET `{OS_SERVICE_BASE_URL}/internal/service-orders/{serviceOrderId}/quotation-context` | UUID no path | `serviceOrderId`, `status`, `customerEmail`, `items` |
| POST `{INVENTORY_SERVICE_BASE_URL}/internal/inventory/consultar-precos` | JSON `inventoryItemIds`: lista de UUIDs ordenados | Objeto `items` com `inventoryItemId`, `salePrice`, `currency` |

Contexto OS: `items` obrigatório; cada item tem `serviceOrderItemId`, `automotiveServiceId`, `serviceAmount`, `status`, `supplies`. Cada insumo tem `inventoryItemId` e `quantity`. Identidades e listas estruturais ausentes são resposta inválida; status do item/preço/quantidade ausentes têm efeito conforme o cálculo. `customerEmail` ausente/null/vazio bloqueia envio por e-mail, não criação/presencial. Status de item e e-mail são complementos ainda pendentes de confirmação externa.

Request Inventory extraído do cenário Mockoon versionado:

```json
{"inventoryItemIds":["00000000-0000-4000-8000-000000000201"]}
```

Resposta de preços usa `items`; preço pode ser string decimal convertida para BigDecimal pelo adaptador. O mock inclui o item `00000000-0000-4000-8000-000000000201` com `salePrice: "25.50"`, `currency: "BRL"`. A lista deve permitir o cálculo dos insumos solicitados; preço necessário ausente e duplicidade falham no cálculo. Não se presume contrato definitivo de Inventory com base apenas no mock.

OS 404 → 404, 409 → 409, outros 4xx → 502, 5xx → 503. Inventory 404 → 422, demais 4xx → 502, 5xx → 503. Timeout local → 504, conexão indisponível → 503; estrutura inválida → 502. Timeouts padrão: conexão 2s, leitura 5s.

Fontes: [controllers e DTOs](../../src/main/java/br/com/fiap/numberone/billing/budget/api), [adaptadores REST](../../src/main/java/br/com/fiap/numberone/billing/budget/infrastructure/rest), [testes HTTP](../../src/test/java/br/com/fiap/numberone/billing/budget/api) e [mocks](../../docker/mockoon). Pendências em [débitos técnicos](../technical-debt.md).
