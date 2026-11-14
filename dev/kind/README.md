# Kind integrado no Billing

Execute a partir deste diretório ou invoque os scripts por caminho relativo/absoluto.
Os scripts calculam os caminhos; não dependem do diretório corrente nem do workspace antigo.
Mantenha os clones irmãos `numberone-billing-service`, `numberone-service-order` e
`numberone-inventory-service` no mesmo diretório pai. Os overlays de aplicação continuam
nos respectivos repositórios. Não é necessário manter `numberone-kind`.

**Docker Compose:** execução isolada e mocks existentes. O Compose do Billing mantém
Mockoon OS/Inventory; o Compose base da OS mantém seu provedor local configurável.
O overlay opcional `compose.history.yaml` da OS continua disponível com OS/Billing reais,
Inventory Mockoon e LocalStack/DynamoDB, como na rodada 2. Inventory não tinha Compose
neste baseline. Nenhum código precisa ser trocado para alternar modos.

**Kind:** OS + Billing + Inventory reais, PostgreSQL e duas instâncias LocalStack
(SQS e DynamoDB). A composição fornece a infraestrutura compartilhada; as aplicações
reutilizam seus overlays. A composição desliga Mercado Pago, webhook e SMTP, removendo
as dependências de Secrets financeiros/SMTP. A homologação financeira anterior permanece
nos overlays originais, sem copiar suas habilitações para AWS ou para este bootstrap.

## Preparar e criar

Pré-requisitos: Bash 4+, Docker, Kind, kubectl/Kustomize, Python 3, curl; internet para
imagens/dependências. Maven/Java locais não são necessários para os builds Kind.
As tags Java são 25 para OS/Billing e 21 para Inventory.

```bash
cd numberone-billing-service/dev/kind
umask 077
test -e k8s/postgres/.env || cp k8s/postgres/.env.example k8s/postgres/.env
test -e .env || cp .env.example .env
chmod 600 .env k8s/postgres/.env
```

Preencha quatro senhas distintas em `k8s/postgres/.env`, sem aspas. Em `.env`, configure
`BUDGET_NOTIFICATION_ENCRYPTION_KEY` com uma chave local de 32 bytes em base64
(`openssl rand -base64 32` gera uma chave para ambiente **novo**). Ambos são ignorados
pelo Git. Não use credenciais AWS reais. O Secret comercial é necessário para agendar
aprovações, mesmo com o worker SMTP desligado.

Para ambiente existente, preserve as quatro senhas e a chave comercial que já protegem
os dados. Não regenere chaves ao migrar os arquivos. A aplicação do novo bootstrap altera
templates e desliga integrações financeiras; faça isso somente quando desejar atualizar
o ambiente. Nesta preparação não foi aplicado nenhum manifest ao cluster existente.

O padrão de kubeconfig é `$HOME/.kube/numberone-kind-config`. Para outro caminho:

```bash
export KIND_KUBECONFIG="$HOME/.kube/numberone-kind-config"
./scripts/create-cluster.sh
```

Cluster/contexto/namespace: `numberone-local` / `kind-numberone-local` / `numberone-local`.
O script de criação preserva um cluster existente e recusa sobrescrever kubeconfig.
Os demais scripts exigem API em `127.0.0.1`, o cluster esperado e contexto explícito.
Nenhum comando usa EKS ou contexto Kubernetes padrão.

## Construir, inicializar e aguardar

```bash
./scripts/up.sh
```

O script valida as configurações, renderiza em diretório temporário privado, constrói
as imagens sequencialmente e carrega as três tags `:kind` no cluster. Aplica primeiro
PostgreSQL/LocalStack e aguarda os três workloads prontos. O bootstrap PostgreSQL cria
`service_order_db`/`os_user`, `numberone_billing`/`billing_user` e
`inventory_db`/`inventory_user`, com senhas separadas e isolamento de databases.
O bootstrap só roda automaticamente em PGDATA vazio; não apaga dados existentes.
Flyway roda dentro de cada aplicação, com `ddl-auto=validate`.

LocalStack SQS cria Standard `numberone-billing-events` e DLQ
`numberone-billing-events-dlq`; visibility=60s, long poll=20s, retention=345600s,
redrive=5 e DLQ retention=1209600s. DynamoDB cria `vehicle_maintenance_history`
(HASH `vehicleId`/S; RANGE `historyKey`/S). Os scripts apontam explicitamente para
LocalStack com credenciais fictícias. O bootstrap DynamoDB é uma cópia do inicializador
local da OS, para manter o arquivo dentro do load root do Kustomize.

Depois aplica a composição completa, que resolve o hash do Secret PostgreSQL nos três
Deployments. Aguarda Inventory → Billing → OS e executa smoke. Não aplique os overlays
Kind isoladamente: eles dependem dos Secrets resolvidos na composição.

Os builds usam as fontes atuais e não rodam testes. `build-images.sh` executa apenas
build/load; `up.sh` aplica recursos. Para templates inalterados de Deployments existentes,
`up.sh` força restart para utilizar a imagem `:kind` recém carregada. Não há reset automático.

```bash
./scripts/build-images.sh  # etapa de imagem independente
./scripts/status.sh        # leitura: workloads, Services, PVC e EndpointSlices
./scripts/smoke.sh         # leitura + forwards temporários, portas aleatórias
```

O smoke exige health agregado, readiness e liveness `UP` nas três aplicações. Não cria
massa comercial, não confirma pagamentos e não substitui a homologação integrada.

## Port-forward

```bash
./scripts/port-forward.sh
```

Mantém os três forwards no primeiro plano. OS `http://127.0.0.1:18080`, Billing
`http://127.0.0.1:18081`, Inventory `http://127.0.0.1:18082`. **Ctrl-C** ou TERM encerra
somente os processos criados pelo script. Pode configurar `OS_LOCAL_PORT`,
`BILLING_LOCAL_PORT` e `INVENTORY_LOCAL_PORT` se houver conflito. Se mudar a porta Billing,
ajuste a URL comercial no overlay/configuração antes de emitir novos links.

Services internos: OS `numberone-os-service:8080`, Billing
`numberone-billing-service:8081`, Inventory `numberone-inventory-service:8082`;
todos os processos Java escutam em 8080. PostgreSQL `postgres:5432`, SQS
`localstack:4566`, DynamoDB `localstack-dynamodb:4566`.

## Parar e excluir deliberadamente

```bash
./scripts/down.sh
```

Para apenas Java, preservando PostgreSQL, LocalStack, PVC, mensagens e namespace.
Não há exclusão de dados por padrão. `up.sh` restaura as réplicas. SQS/DynamoDB são
efêmeros: reiniciar os Pods LocalStack ou o cluster perde estado do emulador.
PostgreSQL persiste no PVC enquanto o node/cluster existir. Não há backup automático.

Para excluir todo o ambiente **somente quando deliberadamente solicitado**:

```bash
kind delete cluster --name numberone-local
```

Isso pode destruir os dados do PVC e as mensagens. Não execute como teste da migração.
Não use `kubectl delete -k` como desligamento normal.

## Validar estaticamente

Com arquivos `.env` locais preenchidos:

```bash
kubectl kustomize . > /dev/null
for file in scripts/*.sh; do bash -n "$file"; done
for file in k8s/{postgres,localstack,localstack-dynamodb}/*.sh; do sh -n "$file"; done
```

Não publique renderizações: contêm Secrets. Nesta entrega a renderização foi feita em
cópia temporária com valores sintéticos; os `.env` reais não foram copiados. O cluster
existente foi verificado sem apply, restart, scale, exclusão ou consumo de mensagens.

Recuperação de bootstrap PostgreSQL parcial: investigue o log primeiro. O script
`/docker-entrypoint-initdb.d/10-provision.sh` pode ser executado deliberadamente no
`postgres-0`; reaplica senhas/permissões e cria databases ausentes. Não é executado
automaticamente sobre PGDATA existente e não faz correção arbitrária de ownership.
