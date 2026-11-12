# ADR 0001: Banco de dados do Billing Service

**Status:** Aceita

**Data:** 2026-11-05

## Contexto

O Billing Service mantém orçamentos e suas revisões comerciais, aprovações e rejeições, cobranças e tentativas de pagamento. Esses registros se relacionam: uma cobrança precisa refletir a composição comercial vigente, e suas tentativas precisam permanecer associadas para permitir acompanhar o resultado financeiro. O serviço também integra-se ao Mercado Pago e publicará eventos conforme os contratos de integração.

A decisão de infraestrutura prevê uma instância Amazon RDS PostgreSQL compartilhada pela plataforma, com databases lógicos isolados por serviço. O Billing será dono de seu database lógico e acessará somente os próprios dados.

O destino RDS é uma decisão de arquitetura; este repositório não comprova provisionamento ou deployment. O ambiente local executa PostgreSQL 17 pelo Compose.

## Decisão

Utilizar PostgreSQL no Amazon RDS como banco transacional principal do Billing Service, em um database lógico exclusivo do serviço.

As transações e restrições de integridade ficam limitadas ao database do Billing. Elas devem manter consistentes, em uma mesma operação local, as alterações relacionadas à revisão comercial, à cobrança e às tentativas de pagamento. Entidades de domínio permanecerão separadas das entidades JPA. O Flyway e suas migrations ficarão no repositório Billing. O serviço não acessará diretamente os bancos de outros serviços; integrações externas seguirão os contratos documentados.

## Alternativas consideradas

1. **DynamoDB:** alternativa viável; oferece transações ACID e conditional writes. Não foi escolhida porque o domínio reúne registros relacionados e consultas que se beneficiam de relações explícitas e SQL, enquanto o desenho por chave exige modelar os padrões de acesso antes de cada consulta.
2. **PostgreSQL + DynamoDB:** não foi adotada neste MVP. Não há no escopo documentado uma necessidade de leitura ou escrita que justifique manter os mesmos fluxos entre dois bancos; a combinação acrescentaria operação e tratamento de consistência entre armazenamentos.

## Justificativas

- Revisões, cobranças e tentativas de pagamento têm relações que precisam ser consultadas e preservadas dentro do limite transacional do Billing.
- Restrições relacionais ajudam a impedir referências inválidas entre esses registros; transações locais permitem aplicar mudanças relacionadas em conjunto.
- SQL permite consultar esses dados relacionados sem definir previamente uma estrutura de leitura por chave para cada consulta.
- Spring Data JPA e Flyway atendem à persistência adotada, aproveitando a decisão de infraestrutura PostgreSQL da plataforma.
- Um único banco transacional reduz a complexidade de implementação e operação para o MVP.

## Consequências

### Positivas

- Dados transacionais do Billing permanecem em um único banco relacional, sob responsabilidade exclusiva do serviço.
- Transações e restrições relacionais podem manter coerentes as referências entre revisões, cobranças e tentativas de pagamento.
- Migrations versionadas no repositório Billing acompanham a evolução de seu próprio esquema.

### Negativas e trade-offs

- Como a instância RDS é compartilhada, consumo de conexões, capacidade e disponibilidade são dependências comuns; o Billing precisará respeitar os limites de recursos e permissões de seu database lógico.
- Alterações de esquema exigem migrations versionadas e compatíveis com a aplicação durante sua implantação.
- Se o Billing precisar de capacidade ou disponibilidade independente da instância compartilhada, essa dependência precisará ser reavaliada.
- PostgreSQL requer gerenciamento de conexões e planejamento de capacidade. DynamoDB também poderia atender ao domínio com transações ACID e conditional writes, mas exigiria modelagem orientada aos padrões de acesso; persistência híbrida acrescentaria consistência entre bancos sem benefício necessário identificado para este MVP.

## Referências

- [Restrições do PostgreSQL](https://www.postgresql.org/docs/current/ddl-constraints.html).
- [Transações e consistência no DynamoDB](https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/transaction-apis.html).
