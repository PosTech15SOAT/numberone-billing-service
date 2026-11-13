# Débitos técnicos e dependências externas

Registro de lacunas atuais verificadas no código/configurações. Comportamento implementado está nos documentos temáticos; decisões e evolução permanecem nos ADRs. Itens resolvidos não são mantidos como pendências. Impactos condicionais não representam incidentes comprovados.

## Contratos e autenticação entre serviços

**Situação:** confirmação externa pendente. OS fornece contexto de orçamento (`GET /internal/service-orders/{id}/quotation-context`) e estado para pagamento (`GET /internal/service-orders/{id}`); Inventory fornece preços por POST. Os mocks demonstram apenas o contrato consumido pelo Billing. Status dos itens/e-mail no contexto, rota/erros/projeções e política monetária precisam de acordo com OS/Inventory. A consulta interna do histórico Budget também é uma implementação local, sem aceite externo comprovado.

Os clientes Budget não configuram identidade de serviço. O cliente OS de Payment é deliberadamente desabilitado por padrão; só aceita opt-in exclusivamente local/loopback sem autenticação. Mercado Pago/webhook habilitados recusam profiles diferentes de exclusivamente local. **Impacto:** a jornada completa não está habilitada para EKS.

**Critério de resolução:** confirmar payloads/estados/rotas/erros e implementar/testar a identidade/autorização de serviço e a política operacional de ambiente, sem usar profile local para contornar guardas. Fontes: [Budget HTTP](integrations/budget-http-contracts.md), [Payment HTTP](integrations/payment-http-contracts.md), [ADR 0012](adr/0012-local-test-seller-verification.md).

## Consumo de eventos e coordenação externa

**Situação:** consumidor OS e Inbox fora deste repositório, sem evidência de validação ponta a ponta. `PagamentoConfirmado` usa paymentId=chargeId e paymentVersion=1; o aceite externo desse payload permanece pendente. Aprovação presencial pode produzir `OrcamentoAprovado` sem `OrcamentoEnviado`; o consumidor não pode esperar indefinidamente um evento inexistente.

**Impacto:** aceite SQS não prova aplicação na projeção OS. Sem deduplicação/desordem/contexto, há risco de efeitos repetidos/regressão. Não há coordenador Saga ou consumidor `OSConcluida` no Billing; início de cobrança permanece manual, como limite atual do MVP, não falha do fluxo manual.

**Critério de resolução:** acordar contratos de [eventos](integrations/events.md), obter evidências do consumidor com duplicidade, desordem e aprovação sem envio, além de fila/DLQ/IAM/retenção/alarmes provisionados. Avaliar início automático em escopo próprio, sem presumir sua implementação.

## Recuperação financeira e recebimento durável

**Situação:** reserva permanente/UNKNOWN preservam a identidade, mas não há reconciliador, Inbox de webhook ou intervenção auditável implementada. Queda após commit da reserva pode ocorrer antes/durante POST; sucesso remoto sem associação também permanece bloqueado. A mitigação atual responde 503 vazio à order autenticada sem vínculo, permitindo reentrega após associação, sem gravar a notificação.

**Impacto:** pode haver cobrança aberta com resultado remoto não recuperado, inclusive reserva cuja chamada nunca ocorreu. Dependência de reentrega não garante recuperação. Falha/expiração/cancelamento observados não produzem transição terminal automática. Resultado contraditório posterior a terminal exige intervenção.

**Critério de resolução:** definir evidência e recuperação pela identidade/chave original com capacidades efetivas do provedor, recebimento durável e tratamento de falha de associação. Não liberar por tempo, assumir reserva, trocar chave ou repetir POST para recuperar. Testes atuais cobrem webhook precoce/reentrega; não suprem durabilidade. [Fluxo](flows/payment.md), [ADR 0010](adr/0010-durable-checkout-reservation.md).

## Quitações históricas e eventos em revisão

**Situação:** o fluxo novo confirma PAID e Outbox juntos. PAID legado sem evento não recebe backfill em migration/replay; existência de registros afetados não foi investigada no banco persistente. Outbox pode ficar NEEDS_REVIEW sem procedimento de reativação auditável implementado.

**Impacto:** quitação local pode não estar refletida na OS; evento PENDING/NEEDS_REVIEW é diferente de evento ausente e de mensagem já aceita pelo SQS.

**Critério de resolução:** investigar dados realmente afetados e definir recuperação com identidade/instante comprovados, preservando diagnóstico e evitando dupla publicação/backfill cego. Para eventos existentes, conservar o fato e seu eventId. Não há comando genérico de UPDATE recomendado. [Outbox](flows/outbox.md), [ADR 0011](adr/0011-integral-payment-confirmation-and-outbox.md).

## Precisão e dados históricos

**Situação:** novas operações de domínio Budget/Charge usam centavos exatos por `setScale(2, UNNECESSARY)` sem normalizar. Zeros finais excedentes são aceitos (`10.000`); frações reais são recusadas. O mapper de criação Mercado Pago exige `scale() <= 2`, gerando divergência real após a reserva para valores de maior escala aceitos pelo domínio. Histórico SQL também admite valores/moedas além das guardas novas. Zero automático de Budget é permitido e Payment exige positivo. Budget não valida positividade de cada componente/quantidade nem moeda global.

**Impacto:** valor comercial aceito pode não gerar checkout e ficar UNKNOWN por falha no mapper; dados legados podem impedir nova operação. Não há política operacional completa para esses casos.

**Critério de resolução:** decidir precisão/representação de transporte e tratamento dos históricos com testes de jornada, sem arredondar ou alterar silenciosamente o principal aprovado. Confirmar semântica de moeda/quantidade nos serviços externos antes de criar novas restrições. [Domínio](architecture/domain-model.md), [persistência](architecture/data-model.md).

## Escopo e composição financeira

**Situação:** quitação automática admite exatamente uma transação integral. Múltiplas transações não são somadas. Excedente coerente sobre o principal pode quitar, mas não é classificado como juros/taxas; parcelas e composição contábil não têm persistência própria. Refunds/chargebacks no snapshot impedem aprovação; efeitos posteriores à quitação não geram compensação automática.

**Impacto:** pagamentos divididos, estornos, conciliação detalhada e investigação posterior dependem de evidência fora do modelo atual. Snapshot consultado é transitório e não conserva payload bruto.

**Critério de resolução:** obter definição contratual da composição, modelar somente evidência durável necessária e aprovar regras de múltiplas identidades/compensações em escopo próprio. [Política financeira](flows/payment.md#evidência-necessária-à-quitação).

## Canonicalização HMAC

**Situação:** ORIGINAL seguida de fallback restrito LOWERCASE_COMPATIBILITY atende modalidades registradas no histórico de testes. Falta esclarecer com o provedor a canonicalização entre simulador e emissor automático e se IDs distintos podem diferir apenas por caixa. O código atual não contém o TODO de revisão descrito em uma versão anterior do ADR; o acompanhamento permanece neste registro.

**Risco:** na alternativa, a assinatura autentica a forma minúscula, não a caixa exata. Consulta autenticada usa o ID original e valida referência/valores; não se presume equivalência de identidade.

**Critério de resolução:** orientação oficial aplicável ao produto, avaliação de SDK/emissor e validação de notificações reais/simuladas com canonicalização única antes de remover a compatibilidade. Não converter incondicionalmente nem adicionar fallback de segredos. [ADR 0013](adr/0013-official-webhook-signature-validator.md).

## Isolamento da entrada e homologação operacional

**Situação:** Gateway/Authorizer é decisão da plataforma, não implementação do backend. Acesso direto não é autenticado pelo Billing; header de funcionário é somente auditoria. Não foram comprovados ingresso AWS, permissões, bloqueio de bypass, sobrescrita de headers ou isolamento de túnel. Há registros documentais anteriores de relato de caminho feliz local; evidência reproduzível suficiente de homologação integral não acompanha este repositório.

**Impacto:** exposição indevida é um risco condicionado à infraestrutura, não incidente demonstrado. Sucesso em mocks/provedor isolado não certifica API → banco → webhook → Outbox → OS.

**Critério de resolução:** comprovar isolamento de `/api/admin/**` e `/internal/**`, rotas públicas e CSS necessários, logs sem query/body protegidos e jornada rastreável com as guardas TEST. Definir governança de segredos/vendedor, TLS/egress e exposição; não habilitar produção financeira. [Operação](operations/local-testing.md), [ADR 0006](adr/0006-centralized-authentication.md).

## Retenção, segredos e observabilidade

**Situação:** não há retenção automática das tabelas operacionais, rotação de chave AES/keyring, recuperação de segredos, ingestão/alertas validados, tracing distribuído ou métricas exportadas. SMTP/SQS usam leases e podem duplicar entrega. MDC é local/síncrono; envelopes não herdam a correlação HTTP.

**Impacto:** crescimento de histórico e pendências, perda de conteúdo cifrado por chave trocada e menor capacidade de acompanhamento/reconciliação em operação ampliada.

**Critério de resolução:** definir retenção sem comprometer idempotência/auditoria, plano de rotação/recuperação, parâmetros de workers/fila e instrumentação com a plataforma. Testar isolamento de contexto e ingestão real sem preencher trace.id/span.id manualmente. [Observabilidade](operations/observability.md).

Refatorar `BudgetGatewayImpl` por tamanho não é débito funcional comprovado: eventual divisão precisa de necessidade concreta e preservação da atomicidade. Os alertas Sonar triados não são convertidos automaticamente em bugs; [registro de qualidade](operations/quality.md).
