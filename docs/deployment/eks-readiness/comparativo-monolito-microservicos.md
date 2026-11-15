# Contratos observados

Fontes: working trees do monólito, `postech15soat-infra-cloud/infra/`, raiz de
`postech15soat-infra-database/` e os três microserviços. Terraform foi apenas lido;
**nenhum recurso abaixo tem existência ou disponibilidade AWS comprovada nesta sessão**.
O diretório `infra/` do repositório database é uma configuração antiga distinta:
o workflow `terraform-apply.yml` usa a raiz, com senha master gerenciada pelo RDS.

| Aspecto | Monólito | Microserviços / decisão |
|---|---|---|
| Build | Java 25, multi-stage, recompila no Docker | Billing/OS Java 25; Inventory Java 21. Deploy usa JAR validado no CI; Inventory recebeu alvo `verified`, mantendo build local como padrão. |
| Gatilho | Push main e manual, environment production | Somente manual/main, environment homologation. Sem parâmetro de imagem/ref arbitrário. |
| Autenticação AWS | Chaves temporárias Academy + session token | Mesmo contrato existente, sem OIDC introduzido. Identidade de Pods é uma dependência separada. |
| Banco | `numberone`, credencial master descoberta no RDS | Databases/roles exclusivos; secret de aplicação recusado se for master. SSL `require`. |
| Aplicação | namespace `numberone-production`, 2 réplicas/HPA/PDB | Billing namespace exclusivo fixo; OS/Inventory por variável. Uma réplica; recursos/probes/startup e rollout limitado. HA/HPA não copiados sem capacidade validada. |
| Ingresso | Service NLB interno porta 80, NodePort 30081; workflow aguarda LB e menciona Terraform auth | Micros usam ClusterIP:8080. Não há NLB/Ingress/API Gateway novo comprovado para eles. Não reutilizar NodePort/rotas do monólito sem desenho externo. |
| Autenticação HTTP | Headers confiáveis do Gateway | Baseline sem autenticação HTTP nos micros preservado. Gateway/Lambda Authorizer devem proteger entrada e sobrescrever headers; não expor `/internal` e Actuator publicamente. |
| Configuração | Runtime CM + Secret Kubernetes alimentados pelo workflow | Mesma abordagem, nomes e credenciais isolados; sem Secret Store/CSI adicionado. |
| Migrations | Flyway no startup, baseline-on-migrate no monólito | Flyway no startup dos micros, `ddl-auto=validate`; migrations existentes preservadas. Não copiar baseline-on-migrate. |
| Observabilidade | stdout JSON/logstash, Datadog agent/StatsD | Billing ECS JSON; OS logstash/Datadog existente preservado; Inventory logging existente. Não copiar download remoto não fixado do tracer do monólito. |
| Operação | kubectl fixo, tag SHA, diagnóstico por logs | Digest ECR, tag única por execução, kubectl obtido da versão EKS; revisão de configuração força recarga de CM/Secret; falha interrompe e exige decisão de rollback. |

## O que o Terraform declara e o que não declara

| Recurso/contrato | Classificação e evidência |
|---|---|
| Região `us-east-1`, prefixo `numberone-lab`, EKS `numberone-lab-eks` | Defaults/exemplo em Cloud; `module.eks` declara cluster e node group. Confirmar overrides/outputs e existência externamente. |
| Kubernetes `1.36`; 2 nodes `t3.small`, máximo 2 | Declarações Cloud, não garantia de versão disponível nem de capacidade livre. Workflows adaptam kubectl à versão consultada quando forem executados. |
| VPC 10.0.0.0/16, subnets públicas/privadas | Módulo VPC; EKS/nodes em subnets públicas; RDS em privadas. Rotas, SG efetivos e saída para SQS/DynamoDB precisam validação operacional. |
| Roles EKS/nodes | `data.aws_iam_role`, variáveis obrigatórias; exemplo `LabRole`. São roles preexistentes esperadas, não criadas por esses módulos. Não comprovam autorização do runner nem IAM dos Pods. |
| ECR monólito `numberone-auto-service-api` e Billing `numberone-billing` | Módulos declarados Cloud. Tags Terraform MUTABLE; workflow usa tag única + digest. Output `ecr_repository_url` é do monólito; Billing não tem output dedicado. |
| ECR `numberone-os` | Esperado pelo workflow OS existente; ausente no Terraform inspecionado. |
| ECR `numberone-inventory` | Novo contrato explícito da pipeline Inventory desta entrega; ausente no Terraform inspecionado. Provisionamento externo pendente. |
| RDS `numberone-lab-postgres`, porta 5432, database inicial `numberone` | Raiz database + módulo RDS; PostgreSQL privado com `rds.force_ssl`, secret master gerenciado. Não cria os três databases/roles da aplicação. Endpoint real PENDENTE. |
| `numberone-billing-database` | `billing.tf` declara apenas `aws_secretsmanager_secret`: não existe `secret_version`, role SQL ou database nesse arquivo. Preencher username/password e criar database/role é ação externa. |
| Secrets de banco OS/Inventory | Esperados por ARN nos workflows; nenhum recurso correspondente encontrado. Nomes reais PENDENTES, não foram inventados. |
| SQS/DLQ e DynamoDB | Só contratos/aplicações/LocalStack encontrados; não há provisioning nos dois repositórios de infraestrutura. ARNs, URL/nome da tabela e permissões AWS PENDENTES. |
| API Gateway, VPC Link e Lambda Authorizer | Monólito referencia implantação externa de auth; recursos não encontrados nestes dois repositórios. Integração/routing dos micros não está demonstrada. |
| NetworkPolicy / identidade AWS dos Pods | Não encontradas nos manifests/infra inspecionados. DNS interno vem dos Services; namespace de destino deve constar nas URLs entre serviços. |

Outputs Cloud: `vpc_id`, `public_subnet_ids`, `private_subnet_ids`,
`eks_cluster_name`, `eks_cluster_endpoint`, `eks_cluster_security_group_id`,
`ecr_repository_url`, `aws_region`, `api_node_ports`.
Outputs Database (raiz): `rds_endpoint`, `rds_port`, `rds_identifier`,
`rds_security_group_id`, `rds_master_secret_arn`, `parameter_group_name`,
`cloud_vpc_id`, `cloud_private_subnet_ids`, `application_security_group_id`.
Dependência Database → remote state Cloud no S3 (`cloud_state_bucket`,
`cloud_state_key=cloud/terraform.tfstate`). A simples presença desses outputs não
comprova state aplicado. Não foram lidos states, tfvars privados ou credenciais.
