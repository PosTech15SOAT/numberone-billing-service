# CI e deploy preparados

| Serviço | CI | Deploy manual | Java / imagem |
|---|---|---|---|
| Billing | `.github/workflows/ci.yml`, Integração contínua | `.github/workflows/deploy.yml`, Deploy manual Billing | 25; Dockerfile reutiliza JAR |
| OS | `.github/workflows/ci.yml`, CI | `.github/workflows/deploy.yml`, Deploy manual os | 25; `--target verified` |
| Inventory | `.github/workflows/ci.yml`, CI (novo) | `.github/workflows/deploy.yml`, Deploy manual Inventory (novo) | 21; `--target verified` novo, default local preservado |

Todos os deploys: `workflow_dispatch`, sem inputs adicionais; selecionar **main**.
O checkout e o CI reutilizável referem-se ao mesmo SHA do evento; artifact é específico
do serviço/SHA/execução e retido por um dia. O job validate só roda para main e o job
deploy depende dele. Não existe ref arbitrária nem reaproveitamento de JAR de outro run.
Nenhum workflow foi disparado nesta sessão.

## CI

Billing: pushes feature/**, develop e main; PRs develop/main. OS mantém seus gatilhos
existentes, incluindo codex/** e branches de integração; Inventory tem pushes
main/develop/feature/**/codex/** e PRs main/develop. Checkout sem credenciais persistidas,
Java/cache Maven e `./mvnw --batch-mode clean verify`. Permissão `contents: read`.
Concorrência cancela CI antigo, preservando validações iniciadas por deploy manual.

Billing/OS conservam JaCoCo mínimo 80% e testes Testcontainers; OS conserva validação
offline dos contratos, smoke da imagem e artifacts de relatórios. Billing recebeu
renderização/schema Kubernetes no CI. Inventory usa PostgreSQL 17 descartável do
runner e `I2_POSTGRES_TEST=true`, exercitando reversão/idempotência além dos unitários.
Inventory não possuía JaCoCo/Sonar gate e não ganhou um novo limite arbitrário nesta
rodada. Billing tem configuração Sonar local, mas o workflow exige JaCoCo, não um
Quality Gate Sonar remoto. Artifacts disponibilizam relatórios para inspeção.

Docker/schema Kubernetes são verificados sem publicação no CI. Renderers substituem
imagem obrigatoriamente por digest. Kubeconform valida schema 1.36.0, que corresponde
à versão declarada no Cloud; suporte real do EKS e disponibilidade das actions/imagens
no runner só serão comprovados na execução remota. Nenhum segredo é versionado.

## Deploy

1. CI obrigatório e artifact do mesmo SHA.
2. Validar GitHub variables/secrets antes de autenticar na AWS. Billing valida também
   chave AES/URL pública; Billing/OS recusam URLs locais e SQS de outra conta/região.
3. Configurar sessão Academy com account allowlist; verificar ECR e consultar EKS
   para instalar kubectl compatível; kubeconfig isolado no `$RUNNER_TEMP`.
4. Obter endpoint RDS e secret exclusivo; recusar master, senha vazia e configuração
   incompatível. Criar namespace/configuração Kubernetes. OS não depende mais de
   CM runtime criado manualmente. Os workflows gerenciam esse runtime a partir das
   variáveis do Environment; revisar valores antes de migrar uma instalação existente.
5. Build da imagem com JAR validado; tag `SHA-RUN_ID-RUN_ATTEMPT`; push ECR e resolver
   digest `sha256`. Nada usa `latest` ou deploy por tag mutável.
6. Renderizar apenas overlay AWS, aplicar e esperar rollout até 10 minutos.
   `DEPLOYMENT_REVISION=RUN_ID-RUN_ATTEMPT` vira annotation também no Pod template,
   garantindo recarga de ConfigMaps/Secrets mesmo se o digest não mudar.
7. Health HTTP agregado/liveness/readiness via port-forward loopback com encerramento
   por trap; registro de commit/digest/namespace. Diagnósticos limitados a estado
   dos recursos, sem dump de Secrets ou logs de payload.

OS/Inventory validam manifests/Secret com dry-run server antes do apply; Billing
renderiza localmente e aplica. Validação do schema, admission, RBAC, permissões AWS,
capacidade, migrations e imagem no ECR continuam pendentes até executar AWS.
Secrets são gerados em arquivos privados, não passados em linha de comando como
senha, e removidos por trap. Billing mantém chave comercial no GitHub Secret e a
injeta no Secret Kubernetes; não copiar a chave Kind.

Não há rollback automático. Falha de apply/rollout/health encerra a execução;
rollback requer avaliar migrations, restaurar configurações compatíveis e escolher
o digest anterior. Não reverte banco, mensagens, estoque ou efeitos financeiros.
Os três repos conservam imagens locais/mocks e não passam a fazer deploy em push.
