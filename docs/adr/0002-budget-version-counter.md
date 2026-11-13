# ADR 0002: Contador transacional de revisões de orçamento por OS

**Status:** Aceita

**Data:** 2026-11-05

**Complemento:** 2026-11-06 — serialização por OS e política provisória de revisão vigente.

## Contexto

Cada Budget representa uma revisão comercial identificada por `budgetId`, associada a uma OS por `serviceOrderId`. A integração comercial exige `budgetVersion` crescente por OS e estável entre envio e decisão. Duas criações simultâneas precisam receber versões diferentes, inclusive quando a OS ainda não tem contador.

No monólito, `attachServiceOrder` permite criação para OS em IN_DIAGNOSIS ou REJECTED, e a aprovação muda a OS para APPROVED. No Billing, a consulta à OS pode ainda não refletir a decisão local; por isso o bloqueio de novas revisões também verifica a existência de orçamento aprovado nos próprios dados do Billing.

A restrição `UNIQUE(service_order_id, budget_version)` impede duplicidades no banco, mas não atribui a próxima versão. A persistência deve também evitar consumir uma versão quando a criação do orçamento falha. O domínio e a aplicação permanecem Java puro; a atribuição transacional pertence à infraestrutura PostgreSQL do Billing.

## Decisão

Utilizar a tabela `budget_revision_counter`, com uma linha por `serviceOrderId` e o campo `last_version`. A operação atômica `INSERT ... ON CONFLICT ... DO UPDATE ... RETURNING` cria o contador com versão 1 ou incrementa seu valor. A chave primária resolve a concorrência da primeira inserção, e o PostgreSQL mantém o bloqueio da linha até o fim da transação.

`BudgetGateway.createDraft` recebe a OS e o valor proposto. Sua implementação na infraestrutura obtém a versão, chama a fábrica do domínio e persiste o agregado na mesma transação local, com flush antes do retorno. O contador exige uma transação existente e usa a mesma datasource da persistência JPA. Uma falha no INSERT do orçamento desfaz também a criação ou o incremento do contador.

`sendForApproval`, `approve` e `reject` substituem o antigo `update(Budget)`. Essas operações recebem o orçamento por ID, identificam sua OS por consulta escalar e executam `SELECT last_version FROM budget_revision_counter WHERE service_order_id = ? FOR UPDATE`. Não incrementam o contador. Depois do bloqueio, carregam o estado atualizado da revisão e consultam a referência comercial. A restrição de unicidade em `budget` é preservada.

A tabela auxiliar fornece uma linha estável para coordenar tanto a numeração quanto operações sobre revisões diferentes da mesma OS. Por exemplo, envio de v2 e aprovação de v1 disputam a mesma linha, mesmo alterando Budgets distintos. Usar somente a linha de cada Budget não coordenaria essas duas operações; reutilizar o contador evita uma nova tabela de controle.

`budgetVersion` representa a ordem comercial de criação por OS: duas revisões recebem 1 e 2 mesmo se seus `createdAt` coincidirem ou forem produzidos por instâncias com relógios diferentes. Timestamps permanecem dados de auditoria; não substituem a numeração, não atribuem versões de forma concorrente e não identificam sozinhos a revisão vigente. Criar v2 em DRAFT preserva a elegibilidade de v1 SENT. A referência vigente usa a maior versão entre SENT, APPROVED e REJECTED, incluindo decisões passadas para impedir a reativação de v1 depois da rejeição de v2.

Como política local de revisão vigente, sujeita ao alinhamento externo, bloquear criação de DRAFT e envio de outra revisão quando existir qualquer orçamento APPROVED para a OS. O gateway verifica a aprovação sob o bloqueio do contador. Na criação, o incremento atômico já obtém esse bloqueio; se a guarda recusar a criação, o rollback desfaz o incremento, sem consumir versão. Um DRAFT que já existia antes da aprovação permanece no histórico, mas não pode ser enviado. Não há cancelamento ou reversão da aprovação.

A migration V2 inicializa os contadores com a maior versão já persistida de cada OS. O complemento reutiliza a estrutura existente, sem migration adicional. Consultas externas e cálculo comercial continuam antes da transação curta de persistência. No escopo original do contador, idempotência HTTP, eventos e recuperação distribuída não eram implementados por essa decisão. A Outbox foi acrescentada depois pela ADR 0004; o contador não fornece essas capacidades por si.

## Alternativas consideradas

- **Transactional Counter por OS:** escolhido porque permite atribuição concorrente e rollback junto com o orçamento, usando somente o PostgreSQL já adotado pelo Billing.
- **`MAX(budget_version) + 1`:** sem serialização, duas transações podem escolher o mesmo valor; a constraint detectaria o conflito, exigindo novas tentativas e outra estratégia para a primeira revisão.
- **Sequence PostgreSQL:** uma sequence global não numera por OS e seu avanço não é desfeito pelo rollback da transação. Manter uma sequence por OS acrescentaria objetos e administração desnecessários.
- **Lock em memória:** não coordena múltiplas instâncias do Billing e perde o estado ao reiniciar o processo.

## Consequências

### Positivas

- Criações para uma mesma OS recebem versões distintas, com inicialização concorrente segura.
- OS diferentes usam linhas de contador distintas; uma transação aberta para uma OS não exige aguardar o contador de outra.
- Falhas na persistência preservam o contador anterior, permitindo nova tentativa de criação com a versão disponível.
- O domínio continua responsável por criar o agregado e aplicar suas transições, sem depender de frameworks.

### Negativas e trade-offs

- Criação, envio e decisões da mesma OS compartilham a linha do contador e podem aguardar sua liberação. O lock termina no commit/rollback; consultas REST não podem ocorrer durante essa espera com o lock adquirido.
- O incremento depende de SQL específico do PostgreSQL e acrescenta uma tabela ao esquema.
- Todas as novas revisões precisam usar a operação transacional do gateway; escritas externas ao fluxo podem desalinhar contador e orçamentos.
- A implantação da V2 deve ser coordenada para impedir gravações pelo fluxo antigo durante a inicialização dos contadores.
- Numeração concorrente não oferece idempotência: duas solicitações repetidas ainda podem gerar duas revisões antes de haver aprovação. A tabela coordena transações; as guardas do domínio e a consulta de revisões determinam a elegibilidade comercial.
- O contador precisa existir para cada OS com orçamento, conforme a V2 e o fluxo de criação. Sua ausência em uma transição é uma inconsistência de persistência, não autorização para prosseguir sem serialização.

## Referências

- [ADR 0001: Banco de dados do Billing Service](0001-billing-database.md).
- [Migration V2 do contador](../../src/main/resources/db/migration/V2__create_budget_revision_counter.sql).
- [INSERT e ON CONFLICT no PostgreSQL 17](https://www.postgresql.org/docs/17/sql-insert.html).
- [Bloqueios de linha no PostgreSQL 17](https://www.postgresql.org/docs/17/explicit-locking.html#LOCKING-ROWS).
