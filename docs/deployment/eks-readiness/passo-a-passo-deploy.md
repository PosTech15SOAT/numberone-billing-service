# Execução posterior pelo operador

Este roteiro **não foi executado contra AWS**. Não use manifests `dev/kind` ou
`k8s/overlays/kind` na AWS. As pipelines implantam somente os overlays AWS de cada
repositório. Não é necessária nenhuma confirmação financeira para o deploy técnico;
as duas jornadas bloqueadas com Mercado Pago permanecem pendentes.

## 1. Promover o conjunto revisado

| Serviço | Branch encontrada nesta sessão | Destino exigido pelo deploy |
|---|---|---|
| Billing | `feature/billing-integracao-sem-auth` | `main`, via processo do repositório/develop |
| OS | `codex/remove-http-authentication` | `main`, via processo do repositório/develop |
| Inventory | `feature/remove-http-auth` | `main`, via processo do repositório |

Revise as alterações prévias junto com as desta entrega. Não publique apenas os novos
workflows deixando os overlays/contratos homologados ausentes: há arquivos Kind,
Dockerfiles e alterações Java ainda não versionados no baseline. Veja a lista de
arquivos desta sessão em [alterações](alteracoes-realizadas.md); preserve também o
trabalho anterior. Não versionar `.env`, Secrets gerados, target, logs ou evidências
com dados. Nenhum commit/push/promoção foi feito nesta sessão.

Antes da promoção, execute os `ci.yml` dos três commits finais. Billing/OS exigem
cobertura mínima 80%; Inventory usa PostgreSQL descartável e I2 habilitado.
Se a plataforma não listar o workflow novo do Inventory, ele precisa primeiro estar
presente na branch padrão. Não contorne a guarda de main para testar deploy remoto.

## 2. Conferir recursos e acesso

A existência real dos itens abaixo é **PENDENTE DE VALIDAÇÃO OPERACIONAL**:

- EKS/versão/capacidade/endpoint e autorização do runner; defaults indicam
  `numberone-lab-eks` em `us-east-1`, sem comprovar cluster ativo.
- ECR `numberone-billing`, `numberone-os`, `numberone-inventory`, mesmos conta/região.
  Cloud só declara Billing e monólito; provisionar OS/Inventory fora desta entrega.
- RDS `numberone-lab-postgres` conforme defaults, endpoint disponível, SSL, SG/rota
  dos nodes, espaço/backups e banco por serviço.
- Databases `numberone_billing`, `service_order_db`, `inventory_db`, roles separadas,
  permissão de conexão/DDL/schema para Flyway e suporte a `pgcrypto` no Inventory.
  Não usar master nas aplicações. O Terraform só cria `numberone` inicialmente.
- Três Secrets Manager exclusivos com username/password; Billing tem apenas
  contêiner declarado, OS/Inventory precisam definição/provisionamento externo.
- GitHub Environment homologation, secrets temporários Academy e todas as variables
  da [lista exata](configuracoes-e-credenciais.md), mais chave AES Billing própria.
- URL pública HTTPS de aprovação Billing com DNS/certificado/rota corretos, e
  Gateway/Lambda Authorizer preparado para a superfície pública dos micros.

Antes de ligar workers AWS, confirmar SQS Standard + DLQ, identidade de workload,
políticas e tabela DynamoDB. Para uma tentativa técnica inicial, manter
`OUTBOX_ENABLED=false`, `BILLING_SQS_ENABLED=false`, `HISTORY_DYNAMODB_ENABLED=false`.
Isso valida startup/REST, mas não valida Saga por eventos ou histórico AWS.

## 3. Selecionar um contexto EKS isolado

Com a sessão AWS do operador configurada **posteriormente**, preencha valores reais.
Não reutilize kubeconfig Kind nem selecione contexto por nome presumido:

```bash
export AWS_REGION='<valor confirmado>'
export AWS_ACCOUNT_ID='<conta confirmada>'
export EKS_CLUSTER_NAME='<output confirmado>'
export EKS_KUBECONFIG="$HOME/.kube/numberone-eks-readiness"
export EKS_CONTEXT="numberone-eks-$AWS_ACCOUNT_ID-$EKS_CLUSTER_NAME"
# Comandos AWS desta seção são apenas roteiro para execução posterior.
aws sts get-caller-identity --query Account --output text
aws eks update-kubeconfig --region "$AWS_REGION" --name "$EKS_CLUSTER_NAME" \
  --kubeconfig "$EKS_KUBECONFIG" --alias "$EKS_CONTEXT"
keks() { kubectl --kubeconfig "$EKS_KUBECONFIG" --context "$EKS_CONTEXT" "$@"; }
keks config view --minify -o 'jsonpath={.clusters[0].cluster.server}'
keks get nodes -o wide
```

Compare a conta retornada com `AWS_ACCOUNT_ID` e confirme endpoint/cluster antes de
seguir. Escolha kubectl compatível com a versão real do EKS. Todos os comandos abaixo
usam `keks`; não use `kubectl` sem contexto explícito. O workflow usa seu próprio
kubeconfig temporário isolado e consulta a versão do cluster.

## 4. Inventory primeiro

No GitHub do Inventory, execute **Deploy manual Inventory**, arquivo
`.github/workflows/deploy.yml`, branch **main**, sem inputs adicionais.
Vars específicas: `K8S_NAMESPACE` (exemplo usado no CI: `numberone-inventory`) e
`DB_APP_USERNAME`, além das comuns. ECR obrigatório `numberone-inventory`; DB fixo
`inventory_db`; DB_SECRET_ARN aponta ao secret exclusivo real.

Depois, no terminal preparado:

```bash
export INVENTORY_NS='<K8S_NAMESPACE do Inventory>'
keks -n "$INVENTORY_NS" rollout status deployment/numberone-inventory --timeout=10m
keks -n "$INVENTORY_NS" get pods,service,endpointslices
keks -n "$INVENTORY_NS" logs deployment/numberone-inventory --tail=100
```

Registrar commit/digest no summary do workflow. Flyway deve concluir as migrations
existentes e Hibernate validar o schema; não aceitar CrashLoop ou probe falhando.

## 5. Billing depois

Execute **Deploy manual Billing**, `.github/workflows/deploy.yml`, branch **main**,
sem inputs. Namespace `numberone-billing`, ECR `numberone-billing`, DB
`numberone_billing`. Configure URLs da OS e Inventory com os namespaces AWS reais,
`BUDGET_APPROVAL_BASE_URL` pública e GitHub Secret
`BUDGET_NOTIFICATION_ENCRYPTION_KEY`. Referir uma URL da OS ainda sem Pod é aceitável
no startup Billing; disponibilidade deve ser testada após subir os três serviços.

```bash
export BILLING_NS=numberone-billing
keks -n "$BILLING_NS" rollout status deployment/numberone-billing --timeout=10m
keks -n "$BILLING_NS" get pods,service,endpointslices
keks -n "$BILLING_NS" logs deployment/numberone-billing --tail=100
```

Mercado Pago/webhook/SMTP continuam desligados. Não ativar TEST/Kind em homologation.
Logs de URLs de aprovação podem conter tokens; mantenha diagnóstico em canal privado
e não publique query strings/payloads. Health não comprova envio de e-mail ou pagamento.

## 6. OS por último

Execute **Deploy manual os**, `.github/workflows/deploy.yml`, branch **main**, sem
inputs. ECR `numberone-os`, DB fixo **`service_order_db`**. Configure namespace,
DB_APP_USERNAME, OS_BRANCH_ID e URLs Billing/Inventory. O workflow cria o runtime
ConfigMap; não exige mais criação manual de `branch-id` no cluster.

```bash
export OS_NS='<K8S_NAMESPACE da OS>'
keks -n "$OS_NS" rollout status deployment/numberone-os --timeout=10m
keks -n "$OS_NS" get pods,service,endpointslices
keks -n "$OS_NS" logs deployment/numberone-os --tail=100
```

## 7. Health, migrations e comunicação

Cada pipeline já verifica três healths por HTTP loopback. Para repetir no terminal,
use um serviço de cada vez, deixando o forward em primeiro plano em outro terminal:

```bash
# Exemplo OS; Ctrl-C encerra. Billing/Inventory têm Services :8080 na AWS também.
keks -n "$OS_NS" port-forward --address 127.0.0.1 service/numberone-os-service 18080:8080
```

```bash
curl -fsS http://127.0.0.1:18080/actuator/health/liveness
curl -fsS http://127.0.0.1:18080/actuator/health/readiness
curl -fsS http://127.0.0.1:18080/actuator/health
```

Repita com `numberone-billing-service`/BILLING_NS e
`numberone-inventory-service`/INVENTORY_NS, portas locais 18081 e 18082. Não exponha
Actuator pelo Gateway. Verifique logs Flyway sem exibir Secrets. Em sessão SQL
protegida com credencial do respectivo banco, executar somente leitura:

```sql
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank;
```

Baseline Kind observado: OS 6, Billing 13, Inventory 4 registros bem sucedidos.
Número esperado deve acompanhar as migrations do commit final, não ser fixado por
manipulação da tabela. Não reparar/limpar Flyway automaticamente.

Para validar DNS/rede interna a partir de um Pod, as imagens Java não prometem curl:
use um Pod de diagnóstico aprovado no ambiente. Exemplo para execução deliberada:

```bash
# Imagem de diagnóstico aprovada deve ser preenchida pelo operador.
export DIAGNOSTIC_IMAGE='<imagem aprovada com curl>'
keks -n "$OS_NS" run numberone-http-check --restart=Never --rm -i \
  --image="$DIAGNOSTIC_IMAGE" --command -- curl -fsS \
  "http://numberone-billing-service.$BILLING_NS.svc.cluster.local:8080/actuator/health/readiness"
keks -n "$BILLING_NS" run numberone-http-check --restart=Never --rm -i \
  --image="$DIAGNOSTIC_IMAGE" --command -- curl -fsS \
  "http://numberone-inventory-service.$INVENTORY_NS.svc.cluster.local:8080/actuator/health/readiness"
keks -n "$BILLING_NS" run numberone-http-check --restart=Never --rm -i \
  --image="$DIAGNOSTIC_IMAGE" --command -- curl -fsS \
  "http://numberone-os-service.$OS_NS.svc.cluster.local:8080/actuator/health/readiness"
```

Esses comandos criam/removem somente o Pod temporário nomeado e não comprovam a IAM
ou todos os contratos comerciais. Complete uma jornada controlada de OS/orçamento
com dados próprios de homologação e os contratos da rodada 2; não reutilizar IDs de
roteiros antigos nem fabricar estados financeiros.

## 8. SQS, DLQ e DynamoDB

Confirmar o contrato via operador com permissão somente de leitura:

```bash
export SQS_QUEUE_URL='<URL real da mesma fila nos dois repos>'
aws sqs get-queue-attributes --region "$AWS_REGION" --queue-url "$SQS_QUEUE_URL" \
  --attribute-names All
export HISTORY_DYNAMODB_TABLE='<nome real da tabela>'
aws dynamodb describe-table --region "$AWS_REGION" --table-name "$HISTORY_DYNAMODB_TABLE"
```

Fila Standard (sem FIFO), visibility=60s, long poll=20s, retenção=345600s;
RedrivePolicy com maxReceiveCount=5 para DLQ própria, retenção DLQ=1209600s.
Tabela HASH vehicleId/S, RANGE historyKey/S. Confirmar políticas/identidade dos Pods,
sem chaves Academy nem endpoints LocalStack. Uma chamada do operador não comprova a
permissão do Pod. Use eventos de jornada real de homologação e observe Outbox→SQS→Inbox
OS/atualização da Saga e projeção de histórico; consulte backlog/DLQ sem consumir
mensagens de outra jornada. Não purgar/reinjetar eventos como validação padrão.

Depois da verificação, ligue `BILLING_SQS_ENABLED=true` na OS, configure a URL e repita
o workflow OS; a aplicação verifica os atributos SQS no startup. Ligue
`OUTBOX_ENABLED=true`/SQS_QUEUE_URL no Billing e repita seu workflow. Para histórico,
configure tabela e `HISTORY_DYNAMODB_ENABLED=true` na OS e repita. Não há botão que
valide automaticamente IAM; erro de credencial/fila é falha operacional, não sucesso
presumido. Observar retries, leases, Inbox, PENDING e DLQ após habilitar.

## 9. Rollback com diagnóstico

Se rollout falhar, a pipeline falha e não desfaz automaticamente as configurações.
Primeiro examine, para o serviço afetado:

```bash
# Exemplo Billing; ajuste namespace e deployment para OS/Inventory.
keks -n "$BILLING_NS" describe deployment numberone-billing
keks -n "$BILLING_NS" get pods,events --sort-by=.metadata.creationTimestamp
keks -n "$BILLING_NS" logs deployment/numberone-billing --tail=100
keks -n "$BILLING_NS" rollout history deployment/numberone-billing
```

Registrar digest anterior antes de atualizar. Após avaliar compatibilidade de schema
e restaurar as variáveis/Secrets compatíveis em canal protegido, selecionar revisão
ou digest anterior conhecido:

```bash
export PREVIOUS_DIGEST_URI='<ECR URI@sha256 confirmado do deploy anterior>'
keks -n "$BILLING_NS" set image deployment/numberone-billing "billing=$PREVIOUS_DIGEST_URI"
keks -n "$BILLING_NS" rollout status deployment/numberone-billing --timeout=10m
# Alternativa deliberada: rollout undo deployment/numberone-billing --to-revision=<revisão>
```

OS usa container `os`/deployment `numberone-os`; Inventory `inventory`/`numberone-inventory`.
Revalidar os três healths e a comunicação após qualquer rollback. `rollout undo`
não restaura conteúdo anterior de CM/Secret com nome fixo; restaurar a configuração
compatível é etapa separada. Rollback de imagem não desfaz migrations ou efeitos de
negócio. Database recovery exige plano de backup/restore e decisão operacional própria.
