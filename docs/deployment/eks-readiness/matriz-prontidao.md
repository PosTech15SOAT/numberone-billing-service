# Matriz de prontidão

Estados: **PRONTO ESTATICAMENTE** = arquivo/configuração validado localmente;
**PRONTO COM PENDÊNCIAS EXTERNAS** = preparação local aceita, depende de recursos/
credenciais/execução AWS; **BLOQUEADO** = impedimento conhecido não corrigido;
**NÃO VERIFICADO** = sem evidência suficiente. Nenhum estado significa deploy AWS concluído.

| Serviço | Build | CI | Pipeline deploy | Manifests | DB/Secrets | Integrações | Pronto para tentativa de deploy? |
|---|---|---|---|---|---|---|---|
| Billing | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO COM PENDÊNCIAS EXTERNAS | PRONTO COM PENDÊNCIAS EXTERNAS | PRONTO COM PENDÊNCIAS EXTERNAS |
| OS | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO COM PENDÊNCIAS EXTERNAS | PRONTO COM PENDÊNCIAS EXTERNAS | PRONTO COM PENDÊNCIAS EXTERNAS |
| Inventory | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO ESTATICAMENTE | PRONTO COM PENDÊNCIAS EXTERNAS | PRONTO COM PENDÊNCIAS EXTERNAS | PRONTO COM PENDÊNCIAS EXTERNAS |

CI aqui significa estrutura e comandos locais; status do check no GitHub remoto é
**NÃO VERIFICADO**. SQS/DynamoDB ficam desligados por padrão em AWS até configuração
explícita. Pagamento produtivo está **BLOQUEADO** pelo suporte/validação financeira
fora do escopo; SMTP produtivo permanece desabilitado. Isso não é ocultado por health UP.

## Evidências desta sessão

| Verificação | Resultado local |
|---|---|
| Billing `./mvnw -B verify`, Java 25 em container | BUILD SUCCESS; 1.390 testes, zero falhas/erros/skips; linhas JaCoCo 94,08%, mínimo 80% atendido. |
| OS `./mvnw -B verify`, Java 25 em container | BUILD SUCCESS; 199 reportados, 197 executados sem falha, 2 skips por ausência de I1_INVENTORY_URL/I2_INVENTORY_URL; JaCoCo 93,88%, mínimo 80% atendido. |
| Inventory `./mvnw -B clean verify`, Java 21 host | BUILD SUCCESS; 105 reportados, 104 executados sem falha, 1 teste desabilitado preexistente; I2_POSTGRES_TEST=true e PostgreSQL 17 descartável. |
| Dockerfiles de produção | Três builds concluídos; Billing JAR validado, OS/Inventory target verified; tags locais exclusivas eks-readiness-20261114, sem push. |
| Runtime das três imagens | Read-only filesystem, tmpfs /tmp, UID/GID 10001, heap 65%, PostgreSQL descartável; migrations e nove healths aprovados; dependências AWS/financeiras desligadas. Não é teste de RDS/TLS real. |
| Inventory target local | Build multi-stage de fontes validado separadamente, mantendo compatibilidade do modo Kind. |
| Workflows | Todos os YAMLs parseados e actionlint aprovado nos três repos; não executados no GitHub. |
| Configurações | 21 casos de validação de chave AES/URL pública/URLs internas/UUID/flags/fila-região-conta, incluindo rejeições, passaram sem rede. |
| Kustomize/schema | 5 objetos AWS Billing, 4 OS, 4 Inventory, 26 Kind: kubeconform strict 1.36.0, 39 válidos, zero inválidos/erros/skips. Renderização Kind em cópia temporária com senhas/chave sintéticas. |
| Referências Kind | Dois Secrets gerados resolvidos nos workloads; ConfigMaps/envFrom, namespace, imagem :kind e imagePullPolicy Never conferidos. Sem dependência de Secrets SMTP/MP. |
| Scripts | bash -n/sh -n nos scripts afetados; smoke/port-forward executados a partir da composição nova. Nenhum up/down/create-cluster foi executado. |
| Kind atual | Seis workloads prontos; health agregado/liveness/readiness UP nos três micros; DNS/HTTP interno a partir de Pod existente; SQS attributes/redrive e DynamoDB schema lidos sem consumo/escrita. |
| Migrations Kind (somente leitura) | OS 6, Billing 13, Inventory 4; todos success=true. |
| Compose/Mockoon | Base Billing e OS preservados; arquivos Mockoon e defaults mantidos. Overlay adicional de histórico conservado, alterando apenas caminho do hook SQS para Billing/dev/kind. |
| Infra/monólito | Branch/HEAD e hashes dos arquivos mantidos; nenhum comando Terraform/AWS ou kubectl remoto. |

O helper inicial de leitura DynamoDB, executado dentro da instância LocalStack SQS,
retornou serviço não habilitado. A verificação foi refeita no Pod DynamoDB dedicado,
endpoint localhost:4566, e o schema foi confirmado. Nenhum recurso foi reconfigurado
para contornar a falha do helper. O smoke das aplicações continuou UP.

Artefatos Java detalhados: `target/surefire-reports`, `target/failsafe-reports` e
`target/site` dos respectivos clones (ignorados pelo Git). Logs auxiliares locais
ficaram em `/tmp/numberone-readiness-validation`; não foram copiados dados/credenciais
ou evidências funcionais para a documentação. As suítes não foram reexecutadas após
mudanças somente de documentação; validações estáticas foram repetidas nos arquivos finais.
