# NumberOne Billing Service

## Arquitetura

- Seguir Clean Code e DDD pragmático.
- Organizar o código por domínio: budget e payment.
- Utilizar as camadas api, application, domain e infrastructure.
- Uma classe por UseCase de negócio relevante.
- Manter o domínio independente de Spring e JPA.
- Evitar abstrações sem necessidade.

## Logging

- Preferir logging nos limites externos (API e Infrastructure). Domain e Application permanecem sem logging enquanto não existir necessidade concreta para isso.
- Evitar logs duplicados e abstrações artificiais.
- Emitir JSON Elastic Common Schema (ECS) em stdout usando o suporte nativo do Spring Boot; ECS aqui não significa Amazon ECS. A aplicação será executada no Amazon EKS.
- Utilizar SLF4J 2 Fluent API com `addKeyValue` para atributos do evento: `event.code` em UPPER_SNAKE_CASE, mensagens objetivas em português e IDs em `budget.id`, `budget.version`, `service_order.id`, `notification.id` ou `event.id`, conforme a operação. O formatter ECS organiza esses nomes em objetos JSON aninhados.
- Utilizar MDC somente para contexto compartilhado dentro de uma operação, com limpeza/restauração ao final. Não implementar correlação sem necessidade nem preencher `trace.id` ou `span.id` manualmente; esses campos pertencem à futura instrumentação de tracing.
- Registrar INFO para marcos confirmados, DEBUG para diagnóstico pontual, WARN para falhas recuperáveis e ERROR para falhas definitivas ou que exigem intervenção. Não registrar cada consulta, poll ou passagem de camada.
- Registrar transições comerciais após o commit; nos workers, registrar sucesso somente após a confirmação local. Escolher um único ponto de logging por marco, sem repetir em controller, gateway e repository.
- Não registrar tokens, e-mails, chaves, credenciais, conteúdo protegido ou payloads completos. Mensagens de exceções externas também podem conter segredos: preservar tipos e frames das causas sem serializar essas mensagens. Mensagens técnicas controladas da aplicação podem ser registradas.

## Gateways externos

- Preferir interfaces por serviço externo ou responsabilidade coesa de integração; não exigir uma interface por método.
- Adicionar somente operações necessárias aos consumidores atuais, sem antecipar funcionalidades.
- Aplicar Interface Segregation Principle conforme as necessidades reais dos consumidores, sem limite fixo de métodos.
- Quando uma interface acumular muitas operações ou responsabilidades distintas, propor uma revisão e justificar sua divisão pela coesão e pelo acoplamento antes de implementar.

## Contratos

- O HTML consolidado da Fase 4 é a referência arquitetural.
- Não inventar endpoints, eventos ou regras.
- Endpoints HTTP devem seguir a nomenclatura em português.
- Classes e métodos Java devem utilizar inglês.
- Não modificar contratos compartilhados sem aprovação.

## Saga

- Identificar implementações pendentes relacionadas à Saga.
- Utilizar comentários no formato:
  // TODO: SAGA - descrição

## Testes

- Criar testes para regras de negócio.
- Executar os testes relevantes após alterações.
- Informar testes não executados e seus motivos.

## ADRs

- Criar ADR apenas para decisões arquiteturais relevantes.
- Utilizar docs/adr/TEMPLATE.md.
- Evitar textos genéricos e referências locais absolutas.

## Melhoria contínua das convenções

Durante o desenvolvimento, identifique padrões recorrentes, decisões técnicas e procedimentos que possam melhorar a consistência do projeto.

Quando identificar uma oportunidade:
- Avalie se o padrão é realmente reutilizável e relevante.
- Sugira atualizar o AGENTS.md quando representar uma convenção permanente.
- Sugira criar um template quando existir um formato de documento ou artefato repetitivo.
- Sugira uma skill somente quando houver um procedimento recorrente com etapas bem definidas.
- Evite duplicar instruções já existentes ou criar abstrações desnecessárias.
- Não transforme decisões pontuais em regras globais.
- Prefira verificações automatizadas (testes, linters e CI) para regras que possam ser validadas por ferramentas.

Antes de criar ou modificar instruções, templates ou skills, apresente a justificativa e solicite aprovação.

Mantenha as instruções objetivas, específicas do projeto e atualizadas. Remova ou revise regras obsoletas quando identificar conflitos.

Ao finalizar uma tarefa, mencione oportunidades relevantes de padronização apenas quando houver benefício concreto. Não é necessário sugerir melhorias em todas as tarefas.
