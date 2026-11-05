# NumberOne Billing Service

## Arquitetura

- Seguir Clean Code e DDD pragmático.
- Organizar o código por domínio: budget e payment.
- Utilizar as camadas api, application, domain e infrastructure.
- Uma classe por UseCase de negócio relevante.
- Manter o domínio independente de Spring e JPA.
- Evitar abstrações sem necessidade.

## Contratos

- O HTML consolidado da Fase 4 é a referência arquitetural.
- Não inventar endpoints, eventos ou regras.
- Endpoints HTTP devem seguir a nomenclatura em português.
- Classes e métodos Java devem utilizar inglês.
- Não modificar contratos compartilhados sem aprovação.

## Saga

- Identificar implementações pendentes relacionadas à Saga.
- Utilizar comentários no formato:
  // #TODO: SAGA - descrição

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