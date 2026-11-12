# ADR 0005: Escolha do mecanismo de mensageria para integração entre microsserviços

**Status:** Aceita

**Data:** 2026-11-06

## Contexto

O Billing precisa comunicar à OS fatos comerciais confirmados: envio, aprovação e rejeição de uma revisão. A OS é proprietária da projeção e da eventual coordenação distribuída; o Billing continua proprietário do orçamento. A Outbox PostgreSQL já conserva os eventos junto com as transições, mas precisa de um destino de publicação independente da disponibilidade do consumidor.

A topologia decidida prevê uma fila Billing → OS e uma DLQ. Não há requisito atual de múltiplos grupos de consumidores, processamento de streams ou ordem global de eventos. O orçamento já tem identidade e versão comercial para distinguir revisões; nenhuma escolha de broker substitui essas regras.

Exemplo: o envio de v1 pode continuar pendente quando a aprovação de v2 chega à OS. Mesmo recebendo as mensagens nessa ordem, a OS deve preservar a decisão de v2 e ignorar v1 obsoleta. O mesmo eventId também pode ser recebido outra vez após uma falha de confirmação.

## Decisão

Usar Amazon SQS Standard como destino dos envelopes persistidos em `integration_event_outbox`. O próprio Billing publica por AWS SDK Java 2.x, sem consumidor SQS para esses eventos. A OS será responsável por Inbox, idempotência e validação de revisão/estado.

A decisão por Standard foi aprovada para o Billing. Os nomes `numberone-billing-events` e `numberone-billing-events-dlq` continuam propostas para a plataforma para alinhamento com a plataforma. Não há alteração de eventType, payload ou schemaVersion. A aplicação recebe região e URL da fila por ambiente; produção utiliza a cadeia padrão de credenciais AWS, sem chaves fixas no código.

A publicação utiliza o envelope já armazenado, incluindo eventId e budgetVersion originais. Standard permite entrega duplicada e fora de ordem. A Outbox e o worker também podem duplicar um envio após aceite externo seguido de falha local. A OS deverá deduplicar por eventId no mesmo commit que aplica a projeção, verificar budgetId/budgetVersion e não regredir uma decisão com SENT atrasado.

Não exigir FIFO. Ordenar a chegada ao broker não reconstrói a ordem comercial: dois publicadores poderiam alcançar o SQS em ordem diferente da criação dos fatos. A deduplicação oferecida pelo broker também não substitui a idempotência durável da OS e o reprocessamento após falhas. Nenhum MessageGroupId ou MessageDeduplicationId é enviado.

A aplicação publica usando uma tentativa por chamada do SDK e retentativas duráveis na Outbox. Uma falha antes do aceite permanece no PostgreSQL; falhas de processamento pelo consumidor são tratadas por visibilidade, redrive e DLQ do SQS. Os detalhes de posse e recuperação ficam na ADR 0004.

Para desenvolvimento e testes, a decisão fixou LocalStack 4.14.0, distribuição comunitária Apache 2.0. Mudanças de distribuição/licenciamento motivaram manter a versão histórica, em vez de adotar latest; atualizações exigem reavaliar acesso e compatibilidade. SQS Standard, inicialização idempotente e AWS SDK 2.57.10 foram exercitados pelos testes locais. Não há recursos AWS reais criados pela aplicação ou pelos testes.

## Alternativas consideradas

| Alternativa | Avaliação no NumberOne |
| --- | --- |
| Amazon SQS Standard | Escolhido: fila gerenciada compatível com a topologia prevista, IAM e consumo desacoplado. Exige idempotência e tratamento de mensagens fora de ordem. |
| Amazon SQS FIFO | Oferece ordenação por grupo e deduplicação no broker, mas adiciona configuração que não elimina a validação de revisão nem a Inbox. O fluxo aprovado aceita duplicidade e desordem. |
| RabbitMQ | Permitiria filas e roteamento, mas exigiria outro broker próprio ou serviço gerenciado, além de operação e integração não previstas na plataforma. |
| Apache Kafka | O log particionado e os grupos de consumidores seriam úteis para streams e outros padrões de replay. Esses requisitos não existem neste fluxo; adicionar Kafka aumentaria a operação do MVP. |
| Amazon EventBridge | Seria útil para roteamento por regras e múltiplos destinos. A integração atual prevê um consumidor de negócio e uma fila definida; um barramento adicional não é necessário. |

## Consequências

### Positivas

- Publicação e consumo podem avançar independentemente, com pendências preservadas na Outbox durante indisponibilidade.
- A aplicação não precisa operar um cluster de broker próprio.
- A validação usa identidades e revisões comerciais já implementadas, sem depender da ordem de chegada.
- O adaptador AWS fica na infraestrutura; a porta de publicação permanece Java puro.
- O ambiente local cria somente filas em um emulador e permite testar falhas, retomadas e duplicidade.

### Negativas e trade-offs

- O consumidor OS precisa implementar Inbox e tratamento explícito de eventos obsoletos ou sem contexto. Sem isso, o sistema distribuído ainda não tem efeito idempotente de ponta a ponta.
- Não existe garantia de exatamente uma entrega, ordem global ou aplicação da projeção quando o Billing marca PUBLISHED. Esse estado significa aceite pelo SQS.
- SQS cobra por operações; retentativas e duplicidades aumentam as requisições. PostgreSQL também recebe leituras e atualizações operacionais da Outbox.
- A plataforma precisa provisionar fila, DLQ, permissões, visibilidade, retenção, redrive e alarmes. Os padrões locais não são decisões de produção.
- O ambiente de testes com uma versão arquivada do LocalStack tem limitações de manutenção e fidelidade. Uma atualização futura exigirá avaliar licença e acesso; não usar latest implicitamente.
- O emulador local é descartável e não demonstra a durabilidade do SQS real. Reiniciar o Billing preserva a Outbox; recriar o LocalStack sem armazenamento persistente remove mensagens que ele já havia aceitado.

## Referências

- [ADR 0004: Transactional Outbox](0004-transactional-outbox.md).
- [Eventos de Budget e execução local](../integrations/events.md).
- [SQS Standard](https://docs.aws.amazon.com/AWSSimpleQueueService/latest/SQSDeveloperGuide/standard-queues.html).
- [LocalStack: mudança de distribuição e autenticação](https://blog.localstack.cloud/2026-upcoming-pricing-changes/).
- [Licença da versão LocalStack 4.14.0](https://github.com/localstack/localstack/blob/v4.14.0/LICENSE.txt).
