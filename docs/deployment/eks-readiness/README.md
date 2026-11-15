# Preparação EKS — três microserviços

Avaliação de 14/11/2026 (America/Sao_Paulo). **Preparação local concluída; primeira
execução AWS depende de recursos e configurações externas. Nenhuma pipeline foi
validada operacionalmente na AWS.** Não houve consulta/deploy AWS, Terraform,
commit, push, PR ou mudança de domínio/migrations/contratos.

- [Matriz de prontidão e evidências](matriz-prontidao.md).
- [Monólito, infraestrutura e diferenças](comparativo-monolito-microservicos.md).
- [Variáveis, Secrets e contratos](configuracoes-e-credenciais.md).
- [Funcionamento das pipelines](pipelines.md).
- [Roteiro de execução posterior](passo-a-passo-deploy.md).
- [Pendências externas e débitos](pendencias.md).
- [Alterações desta sessão e preservação do baseline](alteracoes-realizadas.md).
- [Kind consolidado no Billing](../../../dev/kind/README.md).

Primeira tentativa técnica: infraestrutura/configuração → Inventory → Billing → OS.
Depois habilitar consumidor OS e publisher Billing com a mesma fila, somente após
validar IAM e contrato SQS. Mercado Pago e SMTP permanecem desligados em AWS.
A URL pública de aprovação é obrigatória na pipeline Billing e deve pertencer ao
ambiente; seu roteamento permanece uma dependência externa.

Baseline funcional aceito: rodada 1, 197 verificações/189 aprovadas/8 bloqueadas;
rodada 2, 125 verificações/123 aprovadas/2 bloqueadas. Confirmação financeira pelo
provedor e entrega quitada continuam fora desta tarefa. Os relatórios originais
permanecem em `numberone-kind/docs/homologacao/`; não foram copiados para o Billing.
