# Configuração para a primeira tentativa

Configure o GitHub **Environment `homologation` em cada repositório**. Valores abaixo
são contratos de código, não evidência de configuração no GitHub/AWS. Todos os valores
reais do ambiente remoto estão PENDENTES DE VALIDAÇÃO OPERACIONAL. Não foram consultados.

## Comum aos três repositórios

| Tipo | Nome exato | Valor/condição |
|---|---|---|
| Variable | `AWS_REGION` | `us-east-1` é o default/exemplo Terraform; preencher conforme ambiente efetivo. |
| Variable | `AWS_ACCOUNT_ID` | Conta de 12 dígitos, PENDENTE; não deduzir de imagens locais antigas. |
| Variable | `EKS_CLUSTER_NAME` | `numberone-lab-eks` com defaults Cloud; confirmar output efetivo. |
| Variable | `ECR_REPOSITORY` | Billing `numberone-billing`; OS `numberone-os`; Inventory `numberone-inventory`. |
| Variable | `DB_IDENTIFIER` | `numberone-lab-postgres` com defaults Database; confirmar output efetivo. |
| Variable | `DB_SECRET_ARN` | ARN completo Secrets Manager, exclusivo do serviço, mesma conta/região. Não usar secret master RDS. |
| Secret | `AWS_ACCESS_KEY_ID` | Sessão temporária Academy do operador/runner. |
| Secret | `AWS_SECRET_ACCESS_KEY` | Mesma sessão. |
| Secret | `AWS_SESSION_TOKEN` | Mesma sessão, renovado junto com as outras duas chaves. |

Os workflows não usam OIDC, `AWS_ROLE_ARN`, `MAIL_*` do monólito ou senhas SQL
armazenadas diretamente como GitHub Secret. IAM do runner precisa permitir leitura
EKS/RDS/Secrets Manager, publicação ECR e autenticação/autorização Kubernetes.
Cluster API/RBAC/EKS access entries para essa identidade continuam PENDENTES.

## Billing

| Tipo | Nome exato | Condição |
|---|---|---|
| Variable | `OS_SERVICE_BASE_URL` | URL HTTP/HTTPS real da OS; entre namespaces usar DNS completo. |
| Variable | `INVENTORY_SERVICE_BASE_URL` | URL real do Inventory. |
| Variable | `BUDGET_APPROVAL_BASE_URL` | HTTPS público do ambiente, caminho exato `/api/public/orcamentos-ordem-servico/decisao`, sem query/fragmento/credenciais. DNS/certificado/rota ainda precisam comprovação. |
| Variable nova | `OUTBOX_ENABLED` | `false` por padrão. Só `true` após fila/IAM e consumidor OS preparados. |
| Variable nova | `SQS_QUEUE_URL` | Obrigatória se Outbox ligada; URL HTTPS SQS Standard da conta/região. Mesma fila em `BILLING_SQS_QUEUE_URL` da OS. |
| Secret novo | `BUDGET_NOTIFICATION_ENCRYPTION_KEY` | Obrigatório; AES de 32 bytes em base64 para proteger dados comerciais mesmo com SMTP desligado. Preservar valor em ambientes com dados; não rotacionar como rotina de deploy. |

Namespace fixo `numberone-billing`. Secret Manager esperado pelo ARN: com defaults
Terraform, contêiner `numberone-billing-database`, ainda sem versão/valor declarado.
Formato SecretString: JSON com `username` (role exclusiva, identificador SQL simples)
e `password` não vazios. Banco **`numberone_billing`**, owner/DDL para Flyway;
usuário diferente do master. Não copiar credencial Kind.

Workflow gera `numberone-billing-runtime-config` (DB_URL SSL, URLs, região e SQS)
e `numberone-billing-secret` (DB_USERNAME/DB_PASSWORD/chave comercial). Overlay usa
`SPRING_PROFILES_ACTIVE=homologation`; readiness inclui banco. Outbox false é só
modo técnico: eventos persistem sem publicação. Não declarar Saga funcional nesse modo.
`MERCADO_PAGO_ENABLED`, `MERCADO_PAGO_WEBHOOK_ENABLED` e
`BUDGET_NOTIFICATIONS_ENABLED` ficam false; não há parâmetros de pipeline para ligá-los.
Nenhuma exceção TEST/Kind ou credencial financeira foi introduzida em AWS.

## OS

| Tipo | Nome exato | Condição |
|---|---|---|
| Variable existente | `K8S_NAMESPACE` | Obrigatória, namespace válido; CI usa `numberone-os`. Fixe a escolha antes de configurar DNS. |
| Variable existente | `DB_APP_USERNAME` | Role SQL exclusiva, igual ao username do secret e diferente do master. |
| Variable nova | `OS_BRANCH_ID` | UUID da filial real do ambiente, PENDENTE; não copiar UUID de fixture por conveniência. |
| Variable nova | `BILLING_BASE_URL` | URL real do Billing, porta AWS 8080. |
| Variable nova | `INVENTORY_BASE_URL` | URL real do Inventory, porta AWS 8080. |
| Variable nova | `BILLING_SQS_ENABLED` | `false` por padrão; true exige fila e IAM. |
| Variable nova | `BILLING_SQS_QUEUE_URL` | Mesma URL configurada no Billing; obrigatória quando consumidor ligado. |
| Variable nova | `HISTORY_DYNAMODB_ENABLED` | `false` por padrão; true exige tabela/IAM. |
| Variable nova | `HISTORY_DYNAMODB_TABLE` | Nome da tabela AWS, obrigatório quando histórico ligado. Nome local de referência `vehicle_maintenance_history`, existência/nome AWS PENDENTES. |

Banco corrigido para **`service_order_db`**. ARN/nome do Secret Manager OS PENDENTES;
formato `{"username":"<role exclusiva>","password":"<valor protegido>"}`.
Workflow gera `numberone-os-database` (chaves `url`, `username`, `password`) e
`numberone-os-runtime` (branch-id, URLs, provedor material HTTP, flags, região,
modo SQS dedicated, endpoints AWS sem override). CM `numberone-os-config` do overlay
usa homologation, scheduler pool=3, heap=65%, metadata EC2 desabilitada.
Recuperação Saga e worker de histórico conservam seus defaults existentes true;
o worker histórico só existe se a integração estiver habilitada. Não se alterou
Inbox, leases, compensação ou a rejeição de quantidades fracionárias.

## Inventory

| Tipo | Nome exato | Condição |
|---|---|---|
| Variable nova | `K8S_NAMESPACE` | Obrigatória; o CI usa `numberone-inventory`. Fixe conforme DNS do ambiente. |
| Variable nova | `DB_APP_USERNAME` | Role SQL exclusiva, igual ao username do secret; não master. |

Banco **`inventory_db`**. Secret Manager exclusivo/nome/ARN e ECR
`numberone-inventory` são dependências novas, não declaradas no Terraform inspecionado.
JSON do secret igual ao da OS. Workflow gera `numberone-inventory-database`
(chaves `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`). Overlay AWS fornece CM
`numberone-inventory-config`, profile homologation, probes completas e heap limitado.
Não recebe configurações SQS, DynamoDB, SMTP ou Mercado Pago.

## Rede, TLS e identidade dos Pods

Se namespaces forem OS `numberone-os`, Billing `numberone-billing` e Inventory
`numberone-inventory`, as URLs concretas são:

| Variável(s) | Valor |
|---|---|
| Billing `OS_SERVICE_BASE_URL` | `http://numberone-os-service.numberone-os.svc.cluster.local:8080` |
| Billing `INVENTORY_SERVICE_BASE_URL`; OS `INVENTORY_BASE_URL` | `http://numberone-inventory-service.numberone-inventory.svc.cluster.local:8080` |
| OS `BILLING_BASE_URL` | `http://numberone-billing-service.numberone-billing.svc.cluster.local:8080` |

Substitua namespaces se a escolha operacional diferir. Kind tem portas de Service
8081/8082 para Billing/Inventory; **não usar essas portas na AWS**. Nenhum serviço
AWS depende de localhost/LocalStack. JDBC é construído pelo workflow com endpoint/porta
retornados por RDS e `sslmode=require`, conforme contrato anterior. Isso exige TLS,
mas não faz validação CA/hostname equivalente a `verify-full`; evolução documentada
como débito, sem alterar contrato de certificado nesta rodada.

ServiceAccounts: `numberone-billing`, `numberone-os`, `numberone-inventory`, sem
automount padrão. Não há role AWS ou associação de identidade criada pelos arquivos.
Para ligar SQS/DynamoDB, preparar externamente uma identidade de workload compatível,
por exemplo EKS Pod Identity (agent + associação por namespace/ServiceAccount + role).
Compatibilidade/permissões no Learner Lab são PENDENTES; não assumir disponibilidade.
Não injetar as chaves Academy nos Pods. Metadata EC2 está desabilitada nos overlays AWS
de Billing/OS, evitando uso implícito da role do node. IRSA não foi configurado/validado;
a OS não declara dependência STS para esse caminho, portanto não presumir suporte.

Permissões mínimas a confirmar, restritas a ARNs reais: Billing `sqs:SendMessage`;
OS `sqs:GetQueueAttributes`, `ReceiveMessage`, `DeleteMessage`, `ChangeMessageVisibility`;
histórico OS `dynamodb:PutItem`, `GetItem`, `Query` (operações usadas no gateway). Eventuais KMS,
políticas da fila e trust policies dependem da configuração real e ficam pendentes.
Secrets Manager é lido pelo runner, não pelo processo Java.
