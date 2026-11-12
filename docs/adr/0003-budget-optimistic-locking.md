# ADR 0003: Controle otimista das transições de orçamento

**Status:** Aceita

**Data:** 2026-11-06

**Complemento:** 2026-11-06 — vigência comercial e bloqueio por OS.

## Contexto

Uma revisão SENT pode receber aprovação e rejeição simultaneamente. Duas requisições que leem SENT validam a transição no domínio, mas a última escrita pode sobrescrever a decisão já confirmada. O mesmo problema ocorre quando dois envios leem DRAFT.

O Billing separa Budget de BudgetEntity por meio de mappers. O gateway anterior recebia um agregado já alterado e executava UPDATE JPQL em massa, sem comparar o estado ou a versão técnica da linha. Adicionar apenas @Version à entidade não protegeria essa consulta.

budgetVersion identifica a revisão comercial crescente por OS. Esse número é preservado no envio e na decisão e não pode ser reutilizado como contador de atualizações da linha.

## Decisão

Usar Optimistic Locking com @Version exclusivamente em BudgetEntity. A migration V4 adiciona row_version BIGINT NOT NULL DEFAULT 0, inicializando também os registros existentes. O Hibernate gerencia os incrementos da versão técnica; o domínio e os DTOs públicos não recebem esse campo.

BudgetGateway oferece operações explícitas sendForApproval, approve e reject por budgetId. Cada implementação identifica a OS por consulta escalar, bloqueia sua linha em budget_revision_counter com SELECT FOR UPDATE e só então lê o estado autoritativo da revisão e a referência comercial. A entidade é recarregada também quando já estava no contexto JPA da transação chamadora, para não decidir sobre dados anteriores à espera pelo lock.

Depois, reconstrói Budget, aplica as guardas de vigência e a transição comercial no domínio, copia os campos mutáveis para a mesma entidade gerenciada e faz flush na transação local. Identidade, revisão comercial, valor proposto e snapshot não são copiados durante a atualização.

A referência comercial é a maior budgetVersion entre SENT, APPROVED e REJECTED. DRAFT não a substitui. Se v1 e v2 estão SENT, somente v2 pode ser decidida. Rejeitar v2 não reativa v1; uma nova tentativa exige uma revisão posterior enviada. Uma tentativa tardia de enviar v1 DRAFT após v2 já enviada também é recusada. Revisões anteriores não mudam de estado nem de row_version apenas por se tornarem obsoletas.

Como política local de revisão vigente, a existência de orçamento APPROVED bloqueia novos DRAFTs e o envio de revisões já criadas para a mesma OS. Se a aprovação de v1 confirmar antes do envio de v2, v1 permanece aprovada e v2 continua DRAFT. Se o envio de v2 confirmar primeiro, a aprovação de v1 é recusada por obsolescência. Não há reversão de aprovação.

budgetVersion e row_version têm propósitos diferentes: v2 nasce com budgetVersion 2 e row_version 0; o envio mantém budgetVersion 2 e leva row_version a 1; sua aprovação mantém budgetVersion 2 e leva row_version a 2. O last_version do contador não é incrementado por nenhuma dessas transições. Datas continuam registrando quando as operações ocorreram, sem escolher a revisão vigente.

Os UseCases coordenam as operações por essas interfaces Java puras. No envio, a consulta de contexto da OS e a validação de e-mail continuam antes da transação local; o estado do orçamento é relido e validado dentro dela. As regras de transição permanecem em Budget, sem anotações de persistência.

Não há update genérico ou UPDATE JPQL em massa. Conflitos otimistas, revisões obsoletas e tentativa de criar/enviar após aprovação retornam HTTP 409 no envelope status, message, errors. Estado incompatível com a transição mantém HTTP 422. Com a serialização por OS, a segunda aprovação/rejeição da mesma revisão normalmente encontra a primeira decisão confirmada e retorna 422, em vez de competir no flush. Não há repetição automática da decisão após conflito.

## Alternativas consideradas

- **Versão técnica no agregado Java puro:** permitiria transportar o token lido até o merge de uma entidade desconectada, mantendo a interface genérica de atualização. Foi descartada para evitar metadados de persistência em Budget e dependência do mapeamento desse token em todos os caminhos de reconstrução.
- **Versão exclusivamente na entidade gerenciada:** escolhida. A transição parte do estado carregado na própria transação e as regras continuam no domínio. Exige operações explícitas no gateway em lugar de persistir um agregado arbitrário já alterado.
- **Atualização SQL condicional:** poderia comparar estado/versão e incrementar o contador explicitamente. Exigiria manter manualmente a verificação e o incremento em cada consulta, duplicando o mecanismo já fornecido por JPA.
- **Bloqueio pessimista somente em Budget:** coordenaria a mesma revisão, mas não protegeria aprovação de v1 contra envio de v2. O complemento bloqueia a linha compartilhada do contador por OS e preserva @Version nas entidades Budget como proteção adicional contra escrita JPA com versão desatualizada.

## Consequências

### Positivas

- Duas transações que alteram a mesma versão da linha não confirmam decisões concorrentes; a perdedora sofre rollback.
- Budget permanece independente de JPA, Spring e metadados técnicos. O mapper atualiza somente o estado mutável da entidade já gerenciada.
- budgetId, budgetVersion, snapshot e valores comerciais permanecem independentes do contador técnico.
- Testes PostgreSQL verificam o bloqueio por OS, ambas as ordens entre envio e aprovação, a independência entre OS e a preservação de dados e versões. Um teste JPA específico continua forçando duas escritas da mesma row_version para comprovar a proteção otimista.

### Negativas e trade-offs

- A invocação dos métodos do domínio acontece dentro do adaptador transacional. Os UseCases de aprovação e rejeição são pequenos e delegam a operação atômica ao gateway.
- Operações futuras precisam respeitar esse caminho de escrita. SQL direto e atualizações JPQL em massa podem contornar @Version.
- O consumidor que recebe 409 precisa consultar o estado atual antes de decidir nova tentativa. Duas aprovações simultâneas não se tornam uma operação idempotente por causa do bloqueio otimista.
- @Version isoladamente protege somente uma revisão. A serialização por OS depende de todas as criações e transições usarem o contador na mesma transação, sempre antes da leitura autoritativa de Budget. A ordem contador → orçamento evita caminhos de lock invertidos.
- A política de vigência e o bloqueio após aprovação são provisórios para alinhamento com a equipe. Um DRAFT criado antes de uma aprovação concorrente pode permanecer inutilizável, mas não é apagado ou cancelado automaticamente. A criação após aprovação é recusada.
- Transações da mesma OS agora podem aguardar o lock do contador; operações sobre OS distintas continuam independentes. O fluxo usa READ COMMITTED do PostgreSQL e consultas posteriores à aquisição do lock.
- Não há atomicidade distribuída com OS. Validação de contexto/e-mail não impede mudança posterior no serviço externo. O commit comercial não confirma aplicação na OS nem entrega ao cliente. A [ADR 0004](0004-transactional-outbox.md) acrescenta o armazenamento transacional dos eventos. O complemento de publicação envia pela Outbox quando habilitada, conforme [ADR 0005](0005-message-broker-selection.md). No escopo original, consumo OS e notificações eram pendentes; a ADR 0007 acrescentou autorizações, SMTP e jornada pública, sem implementar o consumidor OS.

## Referências

- [ADR 0002: Contador de versões de orçamento](0002-budget-version-counter.md).
- [Contratos HTTP de orçamento](../integrations/budget-http-contracts.md).
- [BudgetGatewayImpl](../../src/main/java/br/com/fiap/numberone/billing/budget/infrastructure/persistence/gateways/BudgetGatewayImpl.java).
- [BudgetEntity](../../src/main/java/br/com/fiap/numberone/billing/budget/infrastructure/persistence/entities/BudgetEntity.java).
