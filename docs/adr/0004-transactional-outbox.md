# ADR 0004: Registro transacional dos eventos de integração

**Status:** Aceita

**Data:** 2026-11-06

**Complemento:** 2026-11-06 — publicação SQS Standard, posse e recuperação.

## Contexto

A aprovação de uma revisão altera o orçamento no PostgreSQL e precisa comunicar `OrcamentoAprovado` à OS. Se o Billing confirmar a aprovação e cair antes de enviar ao SQS, a revisão fica APPROVED no Billing enquanto a OS continua aguardando uma decisão. Publicar antes do commit também é incorreto: a OS poderia receber uma aprovação que sofreu rollback.

O Billing já serializa as transições por OS usando `budget_revision_counter` e protege cada `BudgetEntity` com `@Version`. Essas garantias locais não tornam PostgreSQL e SQS uma única transação. A decisão prevê Outbox no Billing e Inbox idempotente no consumidor OS; sua implementação é dependência externa.

## Decisão

Persistir a transição comercial e seu evento na mesma transação PostgreSQL. A migration V5 cria `integration_event_outbox`. O nome descreve eventos de integração e permite que Payment reutilize a tabela quando seus contratos forem implementados; o escopo inicial desta decisão continha somente eventos de Budget.

O fluxo definido inicialmente para Budget é:

```text
BEGIN
  bloquear contador da OS sem incrementá-lo
  recarregar Budget e validar a revisão comercial
  executar a transição no domínio e fazer flush JPA
  inserir envelope completo como PENDING
COMMIT
```

`BudgetGatewayImpl` mantém a transação existente. O repositório JDBC exige uma transação ativa (`MANDATORY`) e usa a conexão vinculada ao mesmo gerenciador JPA. Não abre uma transação independente. Se o flush falhar, não há inserção; se a inserção falhar, a alteração já enviada ao banco pelo flush sofre rollback. O evento também é revertido se a transação chamadora falhar depois da inserção.

Cada transição válida grava um envelope imutável com `eventId` UUID, `eventType`, `schemaVersion=1`, `occurredAt` UTC, `correlationId` UUID e `payload`. O tipo identifica envio, aprovação ou rejeição; `budgetVersion` mantém a identidade comercial da revisão. Não usar `row_version` como versão do evento ou do orçamento.

O envelope é armazenado em JSONB. O repositório insere o envelope e o publicador relê esse conteúdo; as atualizações de publicação alteram exclusivamente os campos operacionais. Não reconstruir o fato a partir do estado posterior de Budget. JSONB pode normalizar espaços e ordem das propriedades, preservando o conteúdo JSON. Dados pessoais não integram o envelope.

Na decisão inicial não havia correlação HTTP; cada comando recebia um UUID no caminho transacional. Esse UUID do envelope continua independente do MDC acrescentado depois. A fábrica também aceita correlação explícita, preparando a reutilização futura sem adicionar um protocolo de tracing agora. `occurredAt` é capturado como `Instant` UTC com resolução de microssegundos, compatível com os timestamps novos em TIMESTAMPTZ. Não é inferido a partir de datas locais históricas.

A unicidade `(budget_id, event_type)` impede dois registros do mesmo fato para uma revisão; envio e decisão da mesma revisão continuam sendo eventos diferentes. Os campos `budget_id` e `budget_version` formam um par opcional para não tornar a tabela compartilhável dependente de um orçamento para todo evento futuro. Os eventos Budget sempre preenchem ambos. Não há chave estrangeira específica de Budget na tabela de integração. Payment não estava implementado no escopo original; a ADR 0011 acrescentou referência e confirmação à mesma Outbox.

Aprovação publica logicamente `approvedAmount` como string decimal exata obtida por `BigDecimal.toPlainString()`. Não há arredondamento, conversão para ponto flutuante ou inclusão de `currency`. A decisão inicial preservou a representação decimal histórica. A proposta externa de adicionar moeda permanece sem campo implementado; novas revisões validam centavos exatos, conservando a representação dos valores e sem alterar o contrato do evento.

A modelagem inicial inseriu os eventos como PENDING e preparou os campos operacionais. O complemento de publicação implementou a publicação SQS Standard pelo próprio Billing, sem alterar a V5. Não há geração retroativa de eventos para revisões antigas.

Um scheduler com intervalo e lote configuráveis chama o publicador somente quando habilitado. A reivindicação seleciona um lote disponível com `FOR UPDATE SKIP LOCKED`, grava token UUID por registro, prazo de posse e incremento de attempt_count e confirma uma transação curta. PENDING também representa um registro com posse ativa; não foi necessário criar outro estado.

A seleção e os prazos usam o relógio PostgreSQL, evitando depender do relógio de cada réplica. Enquanto uma transação reivindica linhas, outra pula essas linhas bloqueadas. Depois do commit, outras réplicas deixam de selecioná-las enquanto a posse estiver válida. Não há líder global ou lock de aplicação; três réplicas podem trabalhar sobre registros diferentes.

O publicador lê o envelope persistido e chama o SQS fora da transação. Antes de enviar, verifica se a posse continua válida. Uma resposta com messageId permite atualizar para PUBLISHED, registrar published_at e liberar a posse. A confirmação e o reagendamento exigem event_id, token atual, estado PENDING e prazo ainda válido. Token antigo nunca confirma nem reagenda a posse adquirida por outra réplica.

Os lotes são processados sequencialmente. A configuração exige uma posse maior que `batchSize × apiCallTimeout`; esse limite reduz expirações durante o lote, mas pausas do processo ou falhas locais ainda podem ultrapassá-lo. A verificação anterior ao envio não impede expiração durante a chamada externa. Após expiração pode haver publicação concorrente ou duplicada, e o token protege apenas a escrita local de confirmação.

Uma falha transitória libera a posse e calcula next_attempt_at com backoff exponencial limitado. O SDK faz uma única tentativa por chamada para não multiplicar o número de requisições da política durável. Erros rejeitados que exigem intervenção e esgotamento das tentativas levam a NEEDS_REVIEW. last_error registra somente categorias controladas e status HTTP, sem corpo de resposta AWS, credenciais, conteúdo do envelope ou mensagens arbitrárias de exceptions.

Uma reivindicação conta como tentativa, mesmo se o processo cair antes do envio. A posse expirada permite retomada; se o orçamento de tentativas já tiver acabado, o registro vai a NEEDS_REVIEW sem enviar novamente. Isso evita um ciclo infinito de reivindicações após falhas repetidas. Uma falha de confirmação no banco após sucesso externo mantém a posse para recuperação por expiração, sem disparar uma segunda chamada imediata.

O estado durável permite que uma nova instância continue as pendências após reinício. A recuperação operacional de NEEDS_REVIEW será um procedimento controlado: corrigir a causa, registrar o diagnóstico anterior e reativar o mesmo event_id/envelope com novo orçamento de tentativas. Não há endpoint de reenvio, limpeza automática ou exclusão de registros. Esse procedimento ainda precisa de definição operacional antes de ser automatizado.

Falha de publicação permanece na Outbox, pois a mensagem pode nem ter entrado na fila. A DLQ pertence ao caminho posterior: mensagem aceita pelo SQS que o consumidor não conseguiu processar segundo a política de redrive. Marcar PUBLISHED não confirma aplicação na OS. A seleção do broker e suas alternativas estão na ADR 0005.

Esta decisão trata dos eventos de integração com a OS. A [ADR 0007](0007-budget-approval-notifications.md) acrescenta processamento SMTP separado, sem reutilizar a Outbox SQS. SENT mantém a semântica comercial existente e não depende do resultado de entrega de e-mail.

## Alternativas consideradas

- **Publicação síncrona após o commit:** deixa a janela de perda entre confirmar a revisão e alcançar o SQS. Foi descartada.
- **Publicação antes do commit:** pode comunicar uma decisão que nunca foi confirmada. Foi descartada.
- **Callback em memória após o commit:** organiza a execução, mas perde a pendência se o processo cair; não fornece recuperação durável.
- **Outbox no mesmo PostgreSQL:** escolhida porque participa da transação existente, utiliza Flyway e preserva os mecanismos de concorrência já testados.
- **Outbox no DynamoDB:** DynamoDB suporta transações ACID entre seus próprios itens, mas isso não inclui a atualização de Budget no PostgreSQL. Criaria outra escrita distribuída e infraestrutura sem resolver a atomicidade local necessária.
- **Captura de mudanças com infraestrutura adicional:** poderia publicar alterações persistidas, mas adicionaria componentes e operação ao MVP. O armazenamento local atende ao escopo aprovado.

## Consequências

### Positivas

- Uma aprovação confirmada pelo gateway deixa sua intenção de integração persistida mesmo com SQS indisponível.
- Transição, versão técnica e evento sofrem rollback juntos em caso de erro.
- O envelope conserva a identidade e o valor comercial que existiam no momento do fato, mesmo após outras operações.
- PostgreSQL, transações e serialização permanecem na infraestrutura; domain/application continuam Java puro.
- Testes com PostgreSQL verificam falhas reais de INSERT e UPDATE, concorrência e migração de dados existentes.

### Negativas e trade-offs

- Cada transição passa a depender também da inserção da Outbox. Uma falha local impede confirmar o comando comercial.
- A tabela exige capacidade, acompanhamento de pendências e uma futura política de retenção e recuperação.
- PENDING ainda não comunica nada à OS. O publicador está implementado, mas habilitação/configuração e o consumidor com Inbox na OS são necessários para a integração de ponta a ponta.
- Pode haver publicação duplicada: o SQS aceita a mensagem, mas o Billing cai antes de marcar PUBLISHED. A retomada envia novamente o mesmo `eventId`. O consumidor deve deduplicar na mesma transação que aplica a projeção e validar revisão/estado para evitar regressões.
- A unicidade no banco evita duplicação do fato armazenado, mas não oferece entrega exatamente uma vez, ordem global de mensagens ou idempotência HTTP dos comandos.
- SQL direto que contorne o gateway não registra eventos automaticamente. Os escritores comerciais devem usar o caminho transacional aprovado.
- Moeda, precisão final do contrato compartilhado, correlação propagada e parâmetros operacionais de produção da fila ainda precisam de alinhamento. SQS Standard já foi aprovado para o Billing.

## Referências

- [ADR 0005: Escolha de mensageria](0005-message-broker-selection.md).
- [Contratos dos eventos de Budget](../integrations/events.md).
- [ADR 0003: Concorrência das transições](0003-budget-optimistic-locking.md).
- [AWS: Transactional Outbox](https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html).

## Evolução posterior

A ADR 0011 implementou PagamentoConfirmado na tabela/publicador existentes, com referência charge_id na V12. O filtro HTTP posterior acrescentou MDC local sem alterar a correlação dos envelopes. Os contratos vigentes estão em [eventos](../integrations/events.md) e o comportamento operacional em [Outbox](../flows/outbox.md).
