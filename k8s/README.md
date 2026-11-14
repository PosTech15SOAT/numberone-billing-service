# Billing no EKS — fluxo da Fase 3

A infraestrutura permanece nos repositórios Cloud e Database. O [workflow Billing](../.github/workflows/deploy.yml) publica a imagem e gerencia Namespace, ConfigMaps, Secret e aplicação, como o deploy do monólito. Não há bootstrap de infraestrutura neste repositório. A existência de Terraform não comprova recursos provisionados; nenhuma consulta AWS ou implantação foi executada nesta revisão.

## Processo observado na Fase 3

Foram consultados os workflows em `.github/workflows/` de cada repositório e as configurações que eles realmente executam:

| Repositório | Fluxo efetivo |
| --- | --- |
| Cloud | `terraform-deploy.yml` executa Terraform **em `infra/`**, com state S3. `develop` gera plan; push em `main` ou execução manual autorizada aplica. Provisiona VPC, EKS, ECR do monólito e observabilidade; reutiliza roles IAM informadas por variables. |
| Database | `terraform-plan.yml` e `terraform-apply.yml` executam Terraform **na raiz**, com remote state do Cloud e `terraform.tfvars.example`. O apply ocorre em `main`. Provisiona RDS PostgreSQL privado, SG, TLS obrigatório e senha master gerenciada pelo RDS. A árvore alternativa `infra/` não é usada por esses workflows. |
| Legado | `deploy.yml` roda em push para `main` ou manualmente. Executa Maven com Java 25, consulta endpoint/secret master do RDS, publica imagem ECR, configura EKS, aplica Namespace e cria/atualiza ConfigMap e Secret. Renderiza Kustomize, aplica Deployment e aguarda rollout/health. Tem NLB interno, HPA e PDB próprios do monólito. |

Os três usam sessão AWS Academy temporária (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_SESSION_TOKEN`). Nos arquivos atuais do legado/infra o GitHub Environment é `production`; o Billing conserva o Environment existente **`homologation`**, sem renomear configurações externas. Não foi consultado histórico remoto de execuções; a comparação é dos workflows locais atuais.

As migrations do monólito são executadas pelo Flyway da aplicação, com Hibernate `validate`. O Terraform Database atual cria a instância/database inicial do monólito; **não cria ainda database/role exclusivos do Billing**. Os arquivos SQL em `database/` não são chamados pelos workflows Terraform consultados.

## Nomes e referência arquitetural

Foi confirmado o HTML `NumberOne_plano_arquitetura_distribuida_v2_complemento_saga.html`. Seus nomes propostos não comprovam recursos existentes.

| Recurso Billing | Nome | Origem |
| --- | --- | --- |
| Namespace | `numberone-billing` | Convenção técnica, dedicado e sem ambiente |
| Deployment | `numberone-billing` | HTML NM-003 |
| Service | `numberone-billing-service` | HTML NM-004 |
| ServiceAccount | `numberone-billing` | Convenção técnica |
| ConfigMap da aplicação | `numberone-billing-config` | HTML NM-030 |
| ConfigMap de runtime | `numberone-billing-runtime-config` | Convenção do deploy legado, adaptada ao Billing |
| Secret Kubernetes | `numberone-billing-secret` | HTML NM-026 |
| ECR | `numberone-billing` | HTML NM-021 |
| Database lógico | `numberone_billing` | HTML NM-011 |

O HTML menciona o namespace compartilhado `numberone-production`; ele não será renomeado nem adotado como namespace exclusivo Billing. Mantêm-se os contratos implementados `MERCADO_PAGO_TEST_ACCESS_TOKEN` e `integration_event_outbox`, embora o HTML proponha outros nomes. Não são provisionadas filas nesta implantação.

## Configuração GitHub

O workflow continua **manual**, selecionando `main`, com Environment `homologation`. Manter as chaves de configuração existentes; adicionar somente as necessárias ao Billing:

| Tipo | Nome | Valor esperado |
| --- | --- | --- |
| Variable | `AWS_REGION` | Região real aprovada |
| Variable | `AWS_ACCOUNT_ID` | Conta aprovada, 12 dígitos |
| Variable | `ECR_REPOSITORY` | `numberone-billing` |
| Variable | `EKS_CLUSTER_NAME` | Cluster compartilhado existente |
| Variable | `DB_IDENTIFIER` | Identificador real do RDS compartilhado, como no legado, sem fallback inventado |
| Variable | `DB_SECRET_ARN` | ARN do secret **exclusivo de aplicação Billing**, disponibilizado pelo processo Database; conteúdo JSON `username` e `password` |
| Variable | `OS_SERVICE_BASE_URL` | URL HTTP/HTTPS real do OS, sem credenciais |
| Variable | `INVENTORY_SERVICE_BASE_URL` | URL HTTP/HTTPS real do Inventory, sem credenciais |
| Secret | `AWS_ACCESS_KEY_ID` | Credencial temporária Academy |
| Secret | `AWS_SECRET_ACCESS_KEY` | Credencial temporária da mesma sessão |
| Secret | `AWS_SESSION_TOKEN` | Token da mesma sessão |

Não configurar senha do banco no GitHub. O ARN não contém a senha. A leitura de Secrets Manager replica o mecanismo do legado, mas o Billing **recusa o ARN master do RDS e seu usuário master**. Não adicionar credenciais AWS ao pod nem instalar operador de secrets. Não copiar SMTP, dados financeiros ou configuração comercial do monólito.

## Ordem do deploy da aplicação

1. O workflow reutiliza o CI no mesmo commit: Java 25, `clean verify`, cobertura mínima de 80% e JAR validado. Não repete Maven no job de deploy.
2. Valida configuração, autentica a sessão Academy, verifica o ECR e configura kubeconfig temporário para o EKS.
3. Consulta RDS disponível e lê o secret de aplicação. **Não cria database, role ou recursos AWS.** Monta `DB_URL` para `numberone_billing`, usando endpoint/porta reais e `sslmode=require`.
4. Aplica Namespace e cria/atualiza ConfigMap runtime e Secret, como no legado. Nomes são fixos; não há inputs para objetos externos. O workflow é a fonte desses valores e uma reexecução os sincroniza, inclusive após rotação da credencial na origem. Esta sincronização substitui o bootstrap Kubernetes separado.
5. Publica imagem com tag única `<commit>-<run>-<attempt>` e obtém digest SHA-256 no ECR. O renderizador altera somente a imagem por digest, sem editar arquivos versionados.
6. Aplica Kustomize e aguarda `deployment/numberone-billing` por até 10 minutos. O Flyway do Billing executa V1–V12 no startup; Hibernate continua `validate`.
7. Verifica health agregado, readiness e liveness via port-forward loopback do Service. Falhas coletam estado de objetos, sem imprimir Secrets ou logs da aplicação. Não há rollback automático de migrations.

Credenciais transitam por arquivos temporários 0600 em diretório 0700, removidos ao sair, sem argumentos contendo senha ou saída de conteúdo. Erros da aplicação do Secret são suprimidos e substituídos por mensagem controlada. Não habilitar trace de shell nem publicar esses arquivos como artefatos.

O ConfigMap runtime contém exatamente `DB_URL`, `OS_SERVICE_BASE_URL`, `INVENTORY_SERVICE_BASE_URL`. O Secret contém exatamente `DB_USERNAME`, `DB_PASSWORD`. O Deployment referencia essas chaves obrigatórias e o ConfigMap `numberone-billing-config`; sua ServiceAccount é `numberone-billing`, sem IAM/RBAC adicional ou montagem automática de token.

`sslmode=require` criptografa a conexão RDS, mas não verifica a identidade do servidor. `verify-full` requer CA confiável montada no pod e URL com `sslrootcert` correspondente; essa montagem não faz parte do primeiro deploy. O processo Database deve usar TLS também na preparação administrativa.

## Diferenças necessárias do Billing

- Deployment interno com Service **ClusterIP** 8080. Sem NLB, Ingress, NodePort, HPA ou PDB copiados do monólito. ClusterIP não substitui autenticação entre serviços; não liberar tráfego comercial nesta etapa.
- Database e usuário próprios, com privilégios de migrations apenas dentro do database Billing. Não usar credenciais master no pod.
- Uma réplica, requests 250m CPU/512Mi e limites 1 CPU/1Gi. Rollout usa `maxSurge: 1`, exigindo capacidade temporária para dois pods. Medir antes de ajustar.
- UID/GID 10001, filesystem somente leitura e `/tmp` gravável limitado a 128Mi.
- Profile Spring `homologation` preservado; `local` continua recusado no Kubernetes. Mercado Pago, webhook, Outbox e notificações continuam desabilitados; Payment consulta a OS pela URL configurada sem Bearer. Guardas financeiras TEST permanecem intactas.
- OS/Inventory exigem URLs configuradas, mas não são consultados na criação dos clientes nem nas probes. Podem estar indisponíveis no primeiro smoke; PostgreSQL/Flyway/JPA precisam funcionar. Não testar regras comerciais neste smoke.

| Probe | Path | Configuração |
| --- | --- | --- |
| Startup | `/actuator/health/liveness` | 30 falhas, intervalo 10s, timeout 3s |
| Readiness | `/actuator/health/readiness` | 3 falhas, intervalo 10s, timeout 3s |
| Liveness | `/actuator/health/liveness` | 3 falhas, intervalo 20s, timeout 3s |

As probes usam a porta `http` (8080), sem dependências externas. O smoke inclui `/actuator/health`, que verifica também banco. Encerramento graceful HTTP de 30s e prazo Kubernetes de 120s acomodam o scheduler existente; não copiar o prazo do legado.

## Pendências externas e próxima execução

**Cloud — mudança futura:** adicionar ECR `numberone-billing` reutilizando `infra/modules/ecr` e o workflow/state existentes. Não alterar ECR do monólito, VPC, EKS, roles compartilhadas ou rotas. Revisar o plano completo, incluindo efeitos de `force_delete`/retenção do módulo; se o ECR já existir fora do state, revisar adoção antes de qualquer criação.

**Database — mudança futura necessária:** preparar database lógico `numberone_billing`, role LOGIN exclusiva, ownership/permissões para Flyway V1–V12 e secret de aplicação com `username`/`password`. Centralizar essa preparação no processo do repositório Database, conforme responsabilidade NM-014 do HTML, mantendo RDS e database/role do monólito. O Terraform atual **não oferece essa preparação**; definir sua execução privada e segura nesse repositório antes do deploy. Não alterar `db_name` da instância compartilhada para tentar criar um segundo database e não criar outra instância. Não executar migrations comerciais pelo repositório Database.

**Acesso:** confirmar conectividade RDS a partir dos pods/nodes, capacidade EKS e download ECR. A sessão de deploy precisa das consultas RDS, leitura do secret Billing (e decrypt da chave, se aplicável), publicação ECR e acesso EKS/RBAC para aplicar objetos Billing e port-forward. Não conceder essas permissões ao pod. A sessão do monólito já lê secrets, mas isso não comprova autorização para um novo ARN.

Sequência para executar posteriormente:

1. Responsáveis verificam recursos **reais** por consultas somente leitura: EKS/nodes, RDS disponível, ECR Billing e secret de aplicação. Confirmar versões reais do cluster/kubectl; a declaração Cloud e o workflow usam Kubernetes 1.36. Não reconstruir infraestrutura compartilhada a partir da mera existência do Terraform.
2. Revisar e executar apenas as mudanças exclusivas necessárias pelos fluxos **Cloud e Database**, com seus planos/aprovações. A preparação do database/role ainda é uma pendência, não uma capacidade já implementada.
3. Validar conexão TLS como usuário Billing, permissão de criar objetos no schema da aplicação e ausência de privilégios administrativos globais. Não colocar senha em argumentos, histórico ou Git.
4. Configurar as variables e os três secrets temporários no Environment GitHub `homologation`; disponibilizar alterações aprovadas na branch `main` pelo fluxo normal do projeto.
5. Autorizar e disparar `Deploy manual Billing`. O workflow modifica apenas imagem ECR Billing e objetos Kubernetes Billing; consultas RDS/Secrets Manager são somente leitura. Nenhum bootstrap manual Kubernetes é pré-requisito.
6. Conferir rollout e smoke; confirmar versões 1–12 com `success=true` em `flyway_schema_history`. Não liberar APIs comerciais ou rotas públicas nesta etapa. Em falha, avaliar compatibilidade das migrations antes de qualquer rollback.

Os scripts SQL/Kubernetes de bootstrap e seus testes específicos foram removidos: a configuração Kubernetes passa ao workflow, e a preparação do database retorna ao repositório Database. Não foram substituídos por outro script de provisionamento no Billing.

## Validação local sem AWS ou cluster

```bash
kubectl kustomize k8s/base
kubectl kustomize k8s/overlays/billing
# Fornecer URI com @sha256:<64 caracteres hexadecimais>.
IMAGE_URI="$IMAGE_URI" bash scripts/render-kubernetes.sh
bash -n scripts/*.sh
# Com as ferramentas instaladas: validar YAML renderizado com kubeconform e workflows com actionlint.
git diff --check
```

Esses comandos são locais. Terraform, AWS CLI, kubectl apply e deploy real não integram essa validação.

Referências: [health](../docs/operations/observability.md), [database exclusivo](../docs/adr/0001-billing-database.md).

A variável GitHub `BUDGET_APPROVAL_BASE_URL` é obrigatória no deploy manual e
contém a URL pública HTTPS de decisão desse ambiente. Ela é validada antes de
qualquer acesso AWS e enviada ao ConfigMap runtime; não usa a URL local do Kind.
Configurar o domínio real e o roteamento público antes de habilitar SMTP.

Preparação transversal AWS atual: [roteiro integrado](../docs/deployment/eks-readiness/README.md).
O Kind compartilhado está em `numberone-billing-service/dev/kind`; os overlays deste
repositório continuam sendo reutilizados, com os ajustes de composição feitos na raiz integrada.
