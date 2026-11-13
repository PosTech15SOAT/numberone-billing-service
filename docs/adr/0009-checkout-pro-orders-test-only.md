# ADR 0009: Checkout Pro via Orders API exclusivamente em teste

**Status:** Aceita — política de habilitação original parcialmente substituída pela ADR 0012.

**Data:** 2026-11-09

## Contexto

Após a modelagem local, o próximo passo era criar checkout sem confundir criação de order com quitação. O início permanece manual após OS concluída e Budget aprovado. A decisão financeira do MVP permite somente teste; nome de ambiente/namespace nunca autoriza pagamentos reais.

## Decisão original

Usar POST /v1/orders para order online/processing_mode manual, valor exato, referência de tentativa e chave persistida. Cliente escolhe PIX/cartão no checkout. Não usar Point, coletar cartão ou confirmar pagamento pela criação.

Separar porta Java CheckoutGateway, adaptador RestClient e MercadoPagoOrderMapper. DTOs contêm somente os campos utilizados. RestClient já integra a stack; a escolha evita adotar clientes/modelos do SDK. Posteriormente, ADR 0013 adicionou SDK apenas para assinatura offline, sem substituir transporte.

Exigir cobrança BRL aberta/válida e tentativa PENDING sem operação conhecida. O adaptador não consulta OS nem persiste resultado. A orquestração deve confirmar intenção antes do POST, fora de transação. URL só é aceita no destino HTTPS brasileiro autorizado, sem credenciais/fragmento; dados opcionais de valor/moeda/referência retornados precisam concordar. Resposta não preenche providerPaymentId nem quita Charge.

Integração desabilitada por padrão, TEST explícito e credencial externa. A guarda inicial exigia prefixo TEST-, reconhecido como proteção insuficiente de origem. Inicialmente a política de somente teste era cogitada também para futura implantação; isso nunca comprovou habilitação EKS. O destino HTTPS fixo, redirects desativados, timeouts e ausência de retry/startup I/O permanecem.

Não registrar tokens, headers/payloads ou mensagens externas arbitrárias. Erros preservam categoria/status/causas técnicas sem corpo/headers externos; toString de configuração/resultado protege dados privados. Recuperação de timeout/conflito não autoriza outra chave.

## Alternativas consideradas

- **RestClient:** escolhido por disponibilidade na stack e testes loopback.
- **SDK para transporte:** descartado por modelos/transitivas e comportamento adicional sem necessidade; não impede uso offline posterior.
- **Habilitar só por prefixo:** insuficiente para provar origem de credencial.
- **Pagamento financeiro real:** rejeitado independentemente do ambiente de implantação.
- **Quitação ao criar order:** rejeitada por ausência de evidência financeira.

## Histórico de habilitação

1. **Bloqueio operacional (2026-11-09):** divergência sobre credenciais TEST-/APP_USR- motivou impedir transporte real, mantendo adaptadores simulados. Consulta/observação inicial não fazia confirmação; a capacidade técnica não era homologação.
2. **Verificação de vendedor (2026-11-09, ADR 0012):** substituiu bloqueio absoluto da criação por opt-in exclusivamente local/TEST, token exportado e ID esperado independente, conferido por /users/me. Consulta financeira continuou inicialmente bloqueada.
3. **Consulta e webhook (2026-11-10, revisão da ADR 0012):** ampliou opt-in para GET Orders e confirmação local pelo webhook, verificando titular a cada consulta. EKS/produção continuam bloqueados. TEST-/APP_USR- são apenas formatos aceitos, não comprovação de ambiente.

## Consequências

Transporte permanece isolado e testável, sem dependência de framework no domínio/aplicação. Configuração padrão não chama o provedor; criação não oferece exactly-once e resultado desconhecido exige recuperação. Verificação humana da origem e comparação de titular têm funções distintas. Testes simulados e relatos históricos não certificam a jornada remota completa do Billing nem seus consumidores externos.

## Referências

- [ADR 0008](0008-payment-aggregate-and-local-concurrency.md), [ADR 0010](0010-durable-checkout-reservation.md), [ADR 0012](0012-local-test-seller-verification.md), [ADR 0013](0013-official-webhook-signature-validator.md).
- [Contrato implementado](../integrations/mercado-pago.md), [operação local](../operations/local-testing.md).
- [Referência de criação Orders](https://www.mercadopago.com.br/developers/pt/reference/online-payments/checkout-pro/create-order/post).
