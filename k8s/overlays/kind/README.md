# Configuração comercial no Kind

O overlay injeta `BUDGET_APPROVAL_BASE_URL` pelo ConfigMap runtime e somente
`BUDGET_NOTIFICATION_ENCRYPTION_KEY` pelo Secret `numberone-billing-commercial`.
A chave deve existir antes do rollout: Base64 padrão de exatamente 32 bytes
(AES-256), conforme o contrato atual da aplicação. Seu valor não pertence aos
manifests, ao `runtime.env` ou ao Git.

Para provisionar o Secret a partir do `.env` existente, execute na raiz do Billing
(não execute com tracing de shell):

```bash
python3 - <<'PY' | kubectl --kubeconfig /home/julio/.kube/numberone-kind-config --context kind-numberone-local -n numberone-local apply --server-side --field-manager=numberone-commercial -f -
import base64, json
from pathlib import Path

name = 'BUDGET_NOTIFICATION_ENCRYPTION_KEY'
values = []
for line in Path('.env').read_text().splitlines():
    if '=' not in line or line.lstrip().startswith('#'):
        continue
    key, value = line.split('=', 1)
    if key.strip() == name:
        value = value.strip()
        if len(value) > 1 and value[0] == value[-1] and value[0] in ('"', "'"):
            value = value[1:-1]
        values.append(value)
assert len(values) == 1, 'Chave ausente ou duplicada'
assert len(base64.b64decode(values[0], validate=True)) == 32, 'Chave AES inválida'
print(json.dumps({
    'apiVersion': 'v1', 'kind': 'Secret', 'type': 'Opaque',
    'metadata': {'name': 'numberone-billing-commercial', 'namespace': 'numberone-local'},
    'data': {name: base64.b64encode(values[0].encode()).decode()}
}))
PY
```

O pipeline envia somente a chave ao Kubernetes, sem arquivo intermediário.
Não compartilhe sua saída intermediária. A atualização do Secret não reinicia
automaticamente containers existentes; coordene o rollout do Billing quando houver
rotação. Não gere outra chave para dados já cifrados.

A URL Kind usa `http://localhost:18081/api/public/orcamentos-ordem-servico/decisao`,
configurada em `runtime.env`. Para acesso
pelo navegador na mesma máquina:

```bash
kubectl --kubeconfig /home/julio/.kube/numberone-kind-config --context kind-numberone-local -n numberone-local port-forward --address 127.0.0.1 service/numberone-billing-service 18081:8081
```

Sem token válido, a rota pública deve responder HTTP 404 com a página
“Link inválido”. Isso confirma acesso à rota, mas não uma aprovação comercial.
Um link válido exige orçamento e autorização previamente criados pelo fluxo da
aplicação. O worker SMTP permanece desabilitado: notificações não são enviadas e
seu conteúdo permanece cifrado. Entrega de e-mail depende de configurar e habilitar
SMTP em outra etapa. Mercado Pago e webhook também permanecem desabilitados.

Para renderizar as referências corretas ao Secret compartilhado do PostgreSQL,
use a composição de `numberone-kind`, conforme seu README. Não aplique diretamente
este overlay nem use `up.sh` para uma alteração restrita ao Billing: selecione da
renderização somente o ConfigMap runtime e o Deployment do Billing e confira as
diferenças antes de aplicar. A renderização completa contém Secrets; não a imprima.

## Validação em 14/11/2026

- Chave do `.env` validada como Base64 de 32 bytes; igualdade confirmada no
  container sem imprimir o valor. Secret contém somente essa variável.
- Aplicados somente Secret comercial, novo ConfigMap runtime e Deployment Billing;
  dry-run no servidor e comparação com os recursos anteriores aprovados.
- Rollout concluído, uma réplica Ready, zero reinícios. A primeira espera curta e
  a primeira tentativa HTTP ocorreram antes do fim do startup; os testes abaixo
  passaram após a readiness.
- Health geral, liveness e readiness: HTTP 200, `UP`, via `localhost:8081`.
- Rota pública sem token e com token sintético inválido: HTTP 404, HTML
  “Link inválido”, `Cache-Control` com `no-store`.
- PostgreSQL, LocalStack, OS e Inventory conservaram Pods, containers e contadores
  de reinício. Alterações preexistentes dos arquivos foram preservadas.
- Não foi localizado relatório separado da auditoria anterior nos repositórios;
  a conferência foi feita contra o código, `.env`, manifests e recursos atuais.

Não foi exercitada uma decisão com autorização real nem criado dado comercial.
Não foi identificada dependência adicional para disponibilizar esta configuração;
o envio de e-mails continua dependendo da etapa SMTP, deliberadamente desabilitada.
Nenhum código Java, contrato ou migration foi alterado; testes Java não foram
executados porque a alteração se restringe à configuração e foi validada no Kind.

## Rodada 2 — configuração atual

A URL de aprovação foi alinhada ao forward 18081. Novos envios usam a URL do
ConfigMap; e-mails antigos precisam de reenvio oficial. Em outro dispositivo,
`localhost` não identifica este host: configurar domínio HTTPS acessível.

Mercado Pago TEST possui autorização específica para profile `kind`, namespace
`numberone-local`, `MERCADO_PAGO_KIND_TEST_ENABLED=true`, bases internas de
desenvolvimento e vendedor oficialmente marcado `test_user`. Profiles AWS não
são aceitos. Token, ID esperado e HMAC usam Secret dedicado; credenciais SMTP
usam outro Secret. Flags comerciais ficam no ConfigMap e o worker SMTP termina
desabilitado. SMTP foi exercitado somente no destinatário autorizado.

A URL AWS é fornecida por `vars.BUDGET_APPROVAL_BASE_URL` no workflow e injetada
por ConfigMapKeyRef; domínio HTTPS real deve ser configurado na infraestrutura.
