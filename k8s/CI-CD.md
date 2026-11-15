# CI/CD

- Push em `feature/**` executa CI e procura ou abre PR para `develop`; push em `develop` executa CI e procura ou abre PR para `main` se houver commits a promover. O PR existente recebe os novos commits. Revisão, aprovação e merge são manuais.
- PRs para `develop` e `main` e pushes em `main` também executam CI. O job `test-and-build` publica os relatórios; a falha de automação de PR é independente do resultado do CI. Na OS, PR para `main` deve vir de `develop` no mesmo repositório; seu check existente é preservado.
- A automação usa `GITHUB_TOKEN` com `contents: read` e `pull-requests: write`. Se a criação for bloqueada, habilite **Allow GitHub Actions to create and approve pull requests** em Settings → Actions → General → Workflow permissions, na organização e, se aplicável, no repositório. A política superior pode impedir a configuração. O workflow não aprova PRs; enquanto bloqueado, crie-os manualmente. O GitHub pode exigir que alguém com escrita aprove os workflows do PR criado pelo token.
- Push/merge em `main` executa **Deploy Billing** automaticamente. `workflow_dispatch` permanece disponível em Actions → Deploy Billing → Run workflow → `main`. Os dois jobs exigem `main`; o deploy depende do CI reutilizável da mesma execução, baixa o JAR validado, publica no ECR e aplica a imagem por digest no EKS, com validação dos manifests no servidor, rollout, conferência do digest implantado e três health checks HTTP. PRs e outras branches não fazem deploy. Não há GitHub Environment.
- Organization Variables: `AWS_ACCOUNT_ID`, `AWS_REGION`, `EKS_CLUSTER_NAME`, `DB_IDENTIFIER`. Organization Secrets: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` válidos. Valores previstos: conta `891377201345`, região `us-east-1`, cluster `numberone-eks`, RDS `numberone-postgres`.
- O ARN do secret de banco é descoberto automaticamente com Terraform; não configure `DB_SECRET_ARN` no GitHub. O secret de aplicação no Secrets Manager deve conter `username` e `password` distintos da credencial master. O banco, as permissões AWS/EKS e o acesso de rede devem estar prontos antes do deploy.

Billing: Java 25, Testcontainers, cobertura consolidada mínima de 80%, ECR e namespace `numberone-billing`. URLs internas e de decisão são definidas no ConfigMap do overlay; Repository Secret: `BUDGET_NOTIFICATION_ENCRYPTION_KEY`. Com `OUTBOX_ENABLED=true`, informe também `SQS_QUEUE_URL`. O secret AWS é `numberone-billing-database`.

## Configuração exata do primeiro deploy

| Escopo/tipo | Nome | Valor ou requisito |
| --- | --- | --- |
| Organization Variable | `AWS_ACCOUNT_ID` | `891377201345` |
| Organization Variable | `AWS_REGION` | `us-east-1` |
| Organization Variable | `EKS_CLUSTER_NAME` | `numberone-eks` |
| Organization Variable | `DB_IDENTIFIER` | `numberone-postgres` |
| Organization Secrets | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN` | Mesma sessão Academy válida; organização deve liberar acesso ao repositório Billing. |
| ConfigMap | `INVENTORY_SERVICE_BASE_URL` | `http://numberone-inventory-service.numberone-inventory.svc.cluster.local:8080`. |
| ConfigMap | `OS_SERVICE_BASE_URL` | `http://numberone-os-service.numberone-os.svc.cluster.local:8080`, conforme manifests da OS. |
| ConfigMap | `BUDGET_APPROVAL_BASE_URL` | `https://billing.example.invalid/api/public/orcamentos-ordem-servico/decisao` (temporária, sem acesso público). |
| Repository Secret | `BUDGET_NOTIFICATION_ENCRYPTION_KEY` | Chave própria AES de 32 bytes codificada em base64; não copiar fixture nem rotacionar chave existente como parte do deploy. |
| Repository Variable opcional | `OUTBOX_ENABLED` | Omitir ou `false` no primeiro deploy. |
| Repository Variable condicional | `SQS_QUEUE_URL` | Exigida apenas se `OUTBOX_ENABLED=true`; manter Outbox desligada nesta etapa. |

As configurações comuns também podem existir no repositório. Não criar Environment.
Não é necessário cadastrar `ECR_REPOSITORY`, `K8S_NAMESPACE`, `DB_SECRET_ARN`,
`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, SMTP ou credenciais financeiras no GitHub.
ECR/namespace/banco lógico são fixos no workflow. Terraform descobre o ARN pelo nome
do secret; o runner descobre endpoint/porta no RDS, monta JDBC para
`numberone_billing?sslmode=require` e sincroniza username/password do secret exclusivo.
O secret deve conter JSON com `username` e `password`, diferentes da credencial master.
Esse conteúdo não foi consultado na auditoria.

## Inicialização e evidência operacional

Auditoria AWS somente leitura, conta `891377201345`, região `us-east-1`:
EKS `numberone-eks` ACTIVE (1.36), dois nodes Ready, ECR `numberone-billing`
existente, RDS `numberone-postgres` available e secret `numberone-billing-database`
com `AWSCURRENT`. Inventory 1/1 Running/Ready e health agregado/readiness/liveness
HTTP 200/UP. Namespace Billing ainda ausente; o workflow o cria.

Nodes e RDS estão na mesma VPC. O SG do RDS permite TCP 5432 a partir do SG
efetivamente associado aos nodes; rotas locais presentes e nenhuma NetworkPolicy
encontrada. Isso confirma a conectividade esperada, sem testar login SQL ou comprovar
existência/ownership/permissões do banco `numberone_billing`. Nenhuma senha foi lida.
As consultas `auth can-i` autorizaram criação do namespace, criação/patch dos
recursos usados, acompanhamento do Deployment e port-forward para a sessão local;
não comprovam as credenciais/configurações atualmente cadastradas no GitHub.

O overlay não ativa profile Spring e mantém Outbox/SQS, notificações SMTP e Mercado Pago/webhook desligados.
`OutboxPublicationConfig` e `BudgetNotificationConfig` usam `ConditionalOnProperty`;
os clientes HTTP de OS/Inventory são construídos sem consulta remota no startup.
Uma OS indisponível não impede inicialização; operações que dependem dela exigem
disponibilidade posterior. O overlay usa `application.properties`, sem ativar `local` ou `kind`.

A chave AES é exigida pelo workflow mesmo com SMTP desligado,
pois as operações comerciais persistem notificações protegidas. Com o worker
desligado, a validação dessas propriedades não é executada no startup pelo
`BudgetNotificationConfig`; a URL temporária deve ser substituída por uma URL acessível antes de habilitar notificações.
Outbox desligada acumula eventos e não comprova Saga funcional. Permanecem os
`TODO: SAGA` existentes de reconciliação de identidade de checkout e de PAID
histórico sem EV-03; nenhuma implementação Java foi alterada nesta preparação.

Antes de promover para `main`, confirmar os valores/secrets no GitHub e o banco/role
Billing preparado pelo processo Database. O workflow falha antes do ECR se a
configuração estiver ausente. Não foram executados deploy, apply Terraform,
commit, push ou PR nesta auditoria.

## Validações anteriores

Os resultados abaixo antecedem a configuração das URLs no ConfigMap e a remoção do profile explícito.

`Maven clean verify` com Java 25: 1.077 testes unitários e 313 de integração,
zero falhas/erros/ignorados; cobertura de linhas consolidada de 94,08% (mínimo
preservado em 80%). Imagem Docker construída com o mesmo JAR validado.
Smoke local da imagem com filesystem somente leitura, usuário não root e profile
`homologation` anteriormente configurado: OS indisponível, Inventory EKS disponível via port-forward,
Outbox/SMTP/Mercado Pago desligados; 13 migrations aplicadas em PostgreSQL 17
descartável e três healths HTTP 200/UP. Fixtures locais não são configurações AWS;
esse teste não valida autenticação, TLS ou permissões no RDS real. Containers locais
e port-forward de teste removidos ao final.

Terraform fmt/validate e descoberta do ARN via `console -plan` passaram, sem apply;
actionlint e parsing dos 13 YAMLs passaram; Kustomize renderizou cinco recursos,
todos válidos no kubeconform 1.36.0. Conferidos digest, namespace, referências de
runtime, profile, flags desabilitadas, guardas de main e dependência do CI; quatro
cenários locais do validador de configuração passaram. `git diff --check` sem erros.
Deploy/rollout Billing no EKS e acesso SQL real permanecem não executados por escopo.
