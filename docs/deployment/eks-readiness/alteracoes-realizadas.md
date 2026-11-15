# Alterações realizadas nesta sessão

Trabalho sobre as working trees existentes, sem commits/push/PR, sem promover branches.
A lista abaixo foi obtida comparando hashes anteriores e finais, e não confundindo
`git diff HEAD` (que contém trabalho de rodadas anteriores) com alterações desta sessão.
Não foram alterados Java, pom.xml, regras/contratos, migrations, arquivos Mockoon,
`.env.example` dos serviços nem Compose base nesta sessão.

## Estado inicial preservado

| Repositório | Branch inicial = final | HEAD inicial = final | Estado anterior |
|---|---|---|---|
| numberone-app-auto-service-api | `feature/merge-main-develop` | `da3523d31603bf2ca63a925833cdeaf531fd38dd` | Limpo |
| postech15soat-infra-cloud | `feature/billing-ecr` | `c3b9a4e0b55fe5818c2b6054009ba5fa49002922` | Limpo |
| postech15soat-infra-database | `feature/billing-secrets` | `44a50442c349c122f09c566665730cb1ede193dd` | Limpo |
| numberone-billing-service | `feature/billing-integracao-sem-auth` | `f656fda4953adc31700731715555c9435560f8ff` | 50 entradas de git status, incluindo diretórios untracked |
| numberone-service-order | `codex/remove-http-authentication` | `f6212a743ee8c6f3b19c374fb1da7aedd288cc43` | 69 entradas de git status, incluindo diretórios untracked |
| numberone-inventory-service | `feature/remove-http-auth` | `29ee1eb2a678cf0df7b84216d9fbfe514ee34c80` | 3 entradas de git status, incluindo diretórios untracked |

`numberone-kind` não é um repositório Git; origem mantida sem alterações. O snapshot
completo inicial (status, nomes e hashes, sem conteúdo de arquivos) foi registrado em
`/tmp/numberone-readiness-before.json`, fora do Git. Todos os arquivos preexistentes
foram mantidos; alterações anteriores só receberam edições pontuais nos arquivos
listados abaixo. Monólito, infra-cloud e infra-database não tiveram alteração de hash,
branch ou HEAD. Não foram lidos state Terraform, credenciais ou tfvars privados.

## Correções mínimas e justificativas

| Prioridade | Problema | Correção / verificação |
|---|---|---|
| P0 OS | JDBC usava numberone_os, divergente do banco homologado | service_order_db no workflow; runtime/migrations em PostgreSQL descartável aprovados. |
| P0 Inventory | Sem CI/deploy/overlay AWS; base só recebia senha e probes dependiam de Kind | CI PostgreSQL/Java21, deploy por digest, overlay homologation, Secret DB completo/SA/probes; build, 104 testes executados e runtime aprovados. |
| P1 Billing | Publicação SQS não recebia região/fila/flag no runtime AWS | Injeção explícita com endpoint sem override, validação de URL Standard conta/região; false por padrão até IAM/fila. |
| P1 Billing | Chave comercial necessária ao agendamento não era entregue pelo deploy | GitHub Secret obrigatório AES/base64/32 bytes, injeção no Secret Kubernetes; SMTP continua desligado. |
| P1 OS | Runtime CM dependia de criação manual sem garantia de URLs/filial/flags | Workflow cria CM com variáveis explícitas, provider HTTP, URLs/UUID validados e sem LocalStack em AWS. |
| P1 Kind | Scripts dependiam path absoluto/workspace antigo, tags não acompanhavam build, secrets financeiros preexistentes e hook DynamoDB fora do load root | Composição portátil no Billing, tags kind coerentes, bootstrap DynamoDB contido e baseline financeiro desligado, Secrets locais configuráveis; render/schema/smoke aprovados. |
| P2 | ConfigMap/Secret com nome fixo podia mudar sem reiniciar imagem idêntica | Revisão por execução nas annotations dos Pod templates nos três renderers; verificada estaticamente. |
| P2 | Long poll podia serializar recuperação/histórico OS; heap default sem reserva nativa | Pool 3 e heap 65% no overlay AWS OS; heap/readiness DB Billing e heap/probes Inventory; runtime aprovado. |
| P2 | Billing não validava Kubernetes no CI e usava kubectl fixo no deploy | CI render/schema; main incluída e CI manual preservado de cancelamento; versão kubectl obtida do EKS quando workflow for executado. |

## Arquivos desta sessão

### numberone-billing-service — 45 arquivos

- `.github/workflows/ci.yml` — editado nesta sessão.
- `.github/workflows/deploy.yml` — editado nesta sessão.
- `.gitignore` — editado nesta sessão.
- `README.md` — editado nesta sessão.
- `dev/kind/.env.example` — novo nesta sessão.
- `dev/kind/README.md` — novo nesta sessão.
- `dev/kind/billing-baseline.yaml` — novo nesta sessão.
- `dev/kind/cluster.yaml` — novo nesta sessão.
- `dev/kind/k8s/kustomization.yaml` — novo nesta sessão.
- `dev/kind/k8s/localstack-dynamodb/deployment.yaml` — novo nesta sessão.
- `dev/kind/k8s/localstack-dynamodb/init-dynamodb.sh` — novo nesta sessão.
- `dev/kind/k8s/localstack-dynamodb/kustomization.yaml` — novo nesta sessão.
- `dev/kind/k8s/localstack-dynamodb/service.yaml` — novo nesta sessão.
- `dev/kind/k8s/localstack/deployment.yaml` — novo nesta sessão.
- `dev/kind/k8s/localstack/init-sqs.sh` — novo nesta sessão.
- `dev/kind/k8s/localstack/kustomization.yaml` — novo nesta sessão.
- `dev/kind/k8s/localstack/service.yaml` — novo nesta sessão.
- `dev/kind/k8s/namespace.yaml` — novo nesta sessão.
- `dev/kind/k8s/postgres/.env.example` — novo nesta sessão.
- `dev/kind/k8s/postgres/init.sh` — novo nesta sessão.
- `dev/kind/k8s/postgres/pvc.yaml` — novo nesta sessão.
- `dev/kind/k8s/postgres/service.yaml` — novo nesta sessão.
- `dev/kind/k8s/postgres/statefulset.yaml` — novo nesta sessão.
- `dev/kind/kustomization.yaml` — novo nesta sessão.
- `dev/kind/scripts/build-images.sh` — novo nesta sessão.
- `dev/kind/scripts/common.sh` — novo nesta sessão.
- `dev/kind/scripts/create-cluster.sh` — novo nesta sessão.
- `dev/kind/scripts/down.sh` — novo nesta sessão.
- `dev/kind/scripts/port-forward.sh` — novo nesta sessão.
- `dev/kind/scripts/smoke.sh` — novo nesta sessão.
- `dev/kind/scripts/status.sh` — novo nesta sessão.
- `dev/kind/scripts/up.sh` — novo nesta sessão.
- `docs/deployment/eks-readiness/README.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/alteracoes-realizadas.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/comparativo-monolito-microservicos.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/configuracoes-e-credenciais.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/matriz-prontidao.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/passo-a-passo-deploy.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/pendencias.md` — novo nesta sessão.
- `docs/deployment/eks-readiness/pipelines.md` — novo nesta sessão.
- `k8s/README.md` — editado nesta sessão.
- `k8s/overlays/billing/configmap-patch.yaml` — editado nesta sessão.
- `k8s/overlays/billing/kustomization.yaml` — editado nesta sessão.
- `scripts/render-kubernetes.sh` — editado nesta sessão.
- `scripts/validate-deploy-config.sh` — novo nesta sessão.

### numberone-service-order — 7 arquivos

- `.github/workflows/deploy.yml` — editado nesta sessão.
- `README.md` — editado nesta sessão.
- `compose.history.yaml` — editado nesta sessão.
- `k8s/README.md` — editado nesta sessão.
- `k8s/overlays/homologation/kustomization.yaml` — editado nesta sessão.
- `scripts/render-kubernetes.sh` — editado nesta sessão.
- `scripts/validate-deploy-config.sh` — novo nesta sessão.

### numberone-inventory-service — 11 arquivos

- `.dockerignore` — editado nesta sessão.
- `.github/workflows/ci.yml` — novo nesta sessão.
- `.github/workflows/deploy.yml` — novo nesta sessão.
- `.gitignore` — editado nesta sessão.
- `Dockerfile` — editado nesta sessão.
- `README.md` — editado nesta sessão.
- `k8s/README.md` — novo nesta sessão.
- `k8s/overlays/homologation/configmap.yaml` — novo nesta sessão.
- `k8s/overlays/homologation/kustomization.yaml` — novo nesta sessão.
- `k8s/overlays/homologation/serviceaccount.yaml` — novo nesta sessão.
- `scripts/render-kubernetes.sh` — novo nesta sessão.

"Novo nesta sessão" é comparado à working tree inicial. Dockerfile/.dockerignore e
manifests base/Kind do Inventory já existiam como untracked; foram preservados, e o
Dockerfile/.dockerignore receberam apenas o alvo verified e o JAR no build context.
`compose.history.yaml` da OS também já era untracked: somente o bind do hook SQS foi
reapontado ao Billing. Os overlays Kind originais dos três serviços permanecem intactos.

A consolidação copiou só infraestrutura/scripts de operação necessários; não copiou
`.env`, credenciais reais, estados, logs, dados ou documentos de homologação. O novo
Kind foi validado em cópia com valores sintéticos; nenhuma aplicação da composição
nova foi realizada sobre o ambiente em funcionamento.

Resultados completos e limitações: [matriz](matriz-prontidao.md). As mudanças feitas
após as suítes Java foram exclusivamente pipelines/configuração/documentação; os
artefatos Java e Dockerfiles testados não receberam mudanças posteriores. Não houve
acesso AWS, execução Terraform ou kubectl remoto. A existência remota de recursos e a
execução operacional das pipelines continuam pendentes.
