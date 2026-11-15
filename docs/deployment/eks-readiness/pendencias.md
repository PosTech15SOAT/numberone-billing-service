# Pendências

## Impedimentos reais para a tentativa AWS

Não há bloqueio de build/startup local remanescente nas correções verificadas. A
primeira tentativa remota depende dos itens abaixo, que não foram consultados:

| Prioridade | Impedimento / condição de saída |
|---|---|
| P0 externo | Confirmar EKS ativo/versão suportada/acesso API, RBAC do runner e capacidade para os três Pods e surge de rollout. Cloud declara 2 t3.small, sem prova de folga. |
| P0 externo | Criar/confirmar ECR OS/Inventory e Billing. OS/Inventory não aparecem no Terraform Cloud inspecionado. Confirmar também permissão ECR pull da role de nodes. |
| P0 externo | Disponibilizar os três databases/roles/privilegios e versões válidas de Secrets Manager. O contêiner Billing declarado não fornece senha/database automaticamente. |
| P0 externo | Configurar GitHub variables/secrets nos três Environments homologation, incluindo URL pública e AES Billing. Credenciais/valores remotos não foram lidos. |
| P1 externo | Confirmar DNS e tráfego HTTP entre namespaces nas URLs configuradas, além de SG/rotas EKS→RDS. |
| P1 externo | Para Saga por eventos: fila Standard/DLQ, contrato exato, identidade dos Pods e permissões; habilitar consumer/publisher explicitamente. Não é exigido para startup técnico com flags false. |
| P1 externo | Para histórico: tabela schema correto, identidade/PutItem/GetItem/Query e ativação explícita. Não é exigido para startup técnico com flag false. |
| P1 externo | Publicação externa dos micros: adaptar Gateway/VPC Link/NLB/rotas/Authorizer. Serviços novos são ClusterIP; não herdam exposição/autorização do monólito. |

## Variáveis/Secrets a preencher

A lista normativa está em [configurações](configuracoes-e-credenciais.md). Não há
acesso ao GitHub remoto para distinguir valores ausentes de valores já preenchidos:
classificação **NÃO VERIFICADO**, não afirmação de que todos estão faltando.

Valores reais PENDENTES: conta/cluster efetivo, endpoint RDS (descoberto em execução),
ARNs dos três secrets, usernames exclusivos, namespace OS/Inventory, UUID da filial,
URL pública HTTPS, chave AES, URL/ARNs de SQS/DLQ, tabela e roles de workload.
`numberone-billing-database` é o nome declarado do contêiner com defaults; nomes
Secrets Manager OS/Inventory continuam PENDENTES. Não foram criados nomes fictícios
como se fossem recursos existentes.

## Ações manuais

1. Revisar/promover o conjunto homologado e as correções para main em cada repo.
2. Provisionar os recursos faltantes fora desta sessão; preencher secrets sem expor valores.
3. Preparar Environments/session token Academy; confirmar acesso do runner e dos Pods.
4. Executar Inventory → Billing → OS, observar rollout/health/logs/migrations.
5. Depois da validação de fila/IAM/tabela, habilitar integrações e comprovar jornada
   controlada, Outbox/Inbox, histórico, retries/DLQ e recuperação.
6. Separadamente, concluir com o titular/provedor a confirmação financeira e entrega
   quitada. Não fazem parte desta preparação e não foram simuladas.

## PENDENTE DE VALIDAÇÃO OPERACIONAL

Execução real dos workflows/actions/artifacts no GitHub; sessão/IAM/RBAC; recursos
ECR/EKS/RDS/Secrets; scheduler/capacidade e admission; TLS com RDS real; migrations
sobre dados reais; integração HTTP entre namespaces AWS; API Gateway/Authorizer e
URL pública; SQS/DLQ e DynamoDB reais; rollback remoto e restauração de configuração.
Um sucesso local ou a existência de Terraform não substitui esses resultados.

## Débitos não bloqueantes / P3 não implementados

- HA, HPA/PDB e ajuste fino de recursos/heap/conexões após medição. Não copiamos o
  dimensionamento do monólito para nodes possivelmente pequenos.
- `sslmode=require` preserva o contrato anterior; validar CA/hostname com verify-full
  exige distribuição/gestão de trust bundle e fica para etapa própria.
- OIDC do runner e solução definitiva de identidade dos workloads dependem das
  permissões do Learner Lab. Pod Identity é possibilidade documentada, não recurso criado.
- Pin de todas as imagens/actions e automação completa de rollback/configuração,
  monitoramento/alertas DLQ, backups e retenção de logs.
- Inventory continua sem JaCoCo gate/Sonar; há um teste desabilitado preexistente
  por fixture de preço inválido. OS tem dois testes de integração com Inventory
  real que exigem URLs explícitas; não reexecutados sobre a massa homologada.
- Bootstrap DynamoDB compartilhado foi copiado para manter load root local; alinhar
  essa cópia quando o schema/bootstrap local da OS mudar.
- Kind/LocalStack são efêmeros; não oferecem HA, backup ou durabilidade AWS.
- Clients SQS mantêm o comportamento Java existente. Os modos locais aqui preparados
  fornecem endpoint e credenciais fictícias explícitos; não configurar endpoints
  vazios/credenciais reais em local/Kind. A guarda de histórico local já existente
  permanece. Não foi acrescentada refatoração global de clients nesta rodada.
