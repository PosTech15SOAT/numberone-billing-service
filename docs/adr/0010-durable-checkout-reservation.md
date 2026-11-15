# ADR 0010: Reserva durável para criação manual de checkout

**Status:** Aceita

**Data:** 2026-11-09

## Contexto

O replay local de `startAttempt` devolve a mesma entidade, mas não impede que dois consumidores chamem o provedor depois de liberar o lock. A orquestração de checkout exige confirmar a intenção antes do POST de criação e impedir outro POST entre réplicas, inclusive após queda. Não existe transação atômica entre PostgreSQL e Mercado Pago.

## Decisão

Persistir `checkout_creation_claimed_at` em PaymentAttempt pela V11. Sob o `FOR UPDATE` existente da cobrança, a preparação cria/recupera a raiz, cria a tentativa e adquire a reserva somente se a tentativa nasceu nessa invocação. Tudo confirma em uma única transação. Somente o retorno `acquired=true` dessa invocação autoriza o POST; esse atributo não é reconstruído de um timestamp persistido. Replays nunca renovam, liberam ou assumem uma reserva. PENDING legado sem reserva também não autoriza criação externa.

O UseCase é Java puro. A porta ChargeGateway mantém a responsabilidade coesa de persistir o agregado e preparar atomicamente o checkout. O proxy Spring aplica `Propagation.NEVER` em `prepareCheckout`, recusando transação chamadora; internamente, TransactionTemplate abre uma transação curta e somente retorna depois do commit. Uma chamada interna direta a `createCharge` participa dessa transação explícita, sem depender de interceptação de self-invocation. A preparação usa diretamente FOR UPDATE, evitando promoção concorrente de locks compartilhados na raiz. Leituras normais e operações locais anteriores conservam seus limites existentes.

Após a correção aprovada da verificação local do vendedor, a inspeção local de replay e o GET de identidade antecedem uma nova reserva; falha nessa validação não cria tentativa nem UNKNOWN. A [ADR 0012](0012-local-test-seller-verification.md) detalha a operação de criação vinculada à credencial validada. A preparação revalida as condições locais sob lock. Depois do retorno confirmado da reserva, o UseCase consome essa operação de CheckoutGateway para fazer POST sem transação ativa. A associação da resposta ou UNKNOWN usa outra chamada ao proxy do gateway, com nova transação curta. Não há @Transactional no UseCase nem um commit presumido a partir de flush. Um chamador futuro deve invocar a jornada fora de sua própria transação; há teste que comprova a rejeição antes de reservar ou chamar Mercado Pago.

A consulta de estado da OS fica somente na porta ServiceOrderPaymentGateway. Seu fallback de configuração falha antes da preparação; não simula COMPLETED em produção. Rota, payload e autenticação dependem de acordo com OS Service. O UseCase exige identidade correspondente e COMPLETED do gateway confiável. Budget é consultado localmente: deve haver exatamente um aprovado; o mecanismo existente verifica vínculo, valor e moeda antes de criar/reutilizar Charge. Nenhum valor ou status é aceito do consumidor.

Não há expiração, lease, retomada, scheduler, Redis ou novo mecanismo distribuído. A chave original e attemptId permanecem imutáveis. A criação de order nunca confirma pagamento.

## Alternativas consideradas

- **Somente lock da raiz:** insuficiente depois do commit, quando duas requisições poderiam executar HTTP.
- **Lock durante HTTP:** viola o limite transacional e prende conexão/lock durante latência externa.
- **Confiar somente no header idempotente:** protege o protocolo externo conforme suas regras, mas permite POSTs concorrentes e não resolve a incerteza local.
- **Reserva com expiração ou retomada automática:** rejeitada; tempo decorrido não comprova ausência de aceite remoto.
- **Transação no UseCase inteiro:** rejeitada; a preparação precisa confirmar antes do HTTP. Transações chamadoras são recusadas explicitamente, sem anotações Spring na aplicação.

## Consequências

### Positivas

- A disputa é decidida no PostgreSQL, inclusive entre instâncias independentes de orquestração.
- Resultado com URL persistida é recuperado sem novo POST; repetição conserva datas, versão, identidade e reserva.
- Falha de flush/commit da preparação impede o POST de criação e desfaz também a cobrança recém-criada.
- Logs de reserva, checkout persistido e UNKNOWN são emitidos somente após seus commits, com IDs e sem URL, chave, payload ou mensagens externas.

### Negativas e trade-offs

- A proteção oferece uma única autorização local para criação por tentativa; não promete execução externa exatamente uma vez nem que o POST chegará ao provedor.
- Queda depois do commit e antes/durante o POST deixa PENDING sem URL e com reserva permanente. Repetir retorna esse estado, sem POST. Nova chave continua bloqueada.
- Timeout, comunicação e demais falhas tipadas de criação registram UNKNOWN. Uma recusa da requisição de criação atual não é convertida em falha financeira confirmada. Na correção da verificação local do vendedor, integração desabilitada e falhas da validação anterior à reserva propagam sem criar tentativa; não seguem o tratamento de criação desconhecida. Essa escolha conservadora mantém a reserva e exige reconciliação.
- Sucesso remoto seguido de falha ao persistir produz CheckoutOutcomeUnknownException com chargeId/attemptId e causa preservada; tenta registrar UNKNOWN em nova transação. Se isso também falhar, mantém a causa secundária e a reserva original pode continuar PENDING. Nenhuma dessas falhas autoriza POST posterior.
- Reservas sem resultado conhecido, inclusive as abandonadas antes do POST, exigem recuperação/reconciliação futura com identidade original e recursos oficialmente suportados pela Orders API. O registro de débitos técnicos acompanha essa recuperação. Não existe liberação operacional implementada.
- FAILED/EXPIRED previamente confirmados conservam a reserva antiga e permitem somente uma nova solicitação manual com nova chave, conforme o domínio anterior. O fluxo de criação não confirma esses estados; a confirmação PAID por webhook/evidência foi acrescentada separadamente pela ADR 0011.

## Referências

- [ADR 0008: agregado e concorrência local](0008-payment-aggregate-and-local-concurrency.md).
- [ADR 0009: Orders API exclusivamente de teste](0009-checkout-pro-orders-test-only.md).
- [V11](../../src/main/resources/db/migration/V11__reserve_checkout_creation.sql).
- [Débitos técnicos contratos entre serviços e recuperação financeira](../technical-debt.md).
