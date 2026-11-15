# ADR 0012: Verificação do vendedor e habilitação exclusivamente local TEST

**Status:** Aceita — ampliada em 2026-11-10 para consulta e webhook.

**Data:** 2026-11-09

## Contexto

Prefixos TEST-/APP_USR- não provam origem da credencial; orientações de credenciais de teste motivaram bloquear o transporte até existir uma cadeia explícita de confiança. Comparar identidade autenticada não substitui obter independentemente o vendedor na seção Teste. Uma conta real cadastrada incorretamente como esperada não é detectada como erro de classificação por /users/me.

## Decisão

Manter integração desabilitada por padrão e permitir opt-in exclusivamente com profile local, TEST explícito, token exportado em MERCADO_PAGO_TEST_ACCESS_TOKEN e MERCADO_PAGO_EXPECTED_TEST_SELLER_ID obtido independentemente no painel Teste. O valor configurado deve coincidir com a variável de processo; arquivo/argumento isolado não basta. Prefixos são somente validação sintática. Não habilitar EKS nomeando-o local.

Antes de novo checkout, GET https://api.mercadolibre.com/users/me exige 200, JSON objeto com id inteiro positivo Int64 igual ao esperado. Recusa ambiguidades, excesso de tamanho, tipos, redirect, erro/timeout e divergência, sem cache/retry/fallback. A consulta prova o titular, não ambiente TEST, aplicação, produto ou moeda. O token é o mesmo nas operações subsequentes.

Orders permanece em https://api.mercadopago.com, HTTPS, redirects desativados, timeouts finitos e sem I/O autenticado no startup. Verificador e transportes recusam transação ativa antes de I/O.

## Evolução e ordem das operações

A habilitação original permitia criação após validação do titular, com consulta financeira ainda bloqueada. A correção posterior moveu a validação para **antes de nova reserva**: inspecionar replay local sem escrita, consultar OS correspondente/COMPLETED, validar vendedor fora da transação, revalidar/preparar sob locks e confirmar reserva antes do POST. Falha de identidade deixa de criar nova tentativa/reserva/UNKNOWN.

A validação devolve operação de uso único que captura a credencial na infraestrutura, sem atravessar a aplicação como token. Concorrentes podem validar titular, mas somente o adquirente da reserva consome a operação e faz POST. Outro concorrente retorna resultado local. Falha do commit impede POST; o POST não relê token nem repete GET de identidade. Replay conhecido antecede OS/provedor, mantendo validações comerciais locais.

Em 2026-11-10, a decisão ampliou opt-in a GET Orders e confirmação pelo webhook. Cada consulta verifica /users/me e usa exatamente o token validado. Desabilitado, o gateway permanece BLOCKED. Webhook possui habilitação/TEST/segredo próprios e exige exclusivamente local. Confirmação financeira/Outbox seguem ADR 0011.

Reservas históricas UNKNOWN, falha de associação, timeout e queda entre commit/POST continuam sem recuperação automática. Repetição não libera/renova/assume a reserva nem autoriza novo POST.

## Estratégia de testes e exposição temporária

Um executável alternativo de teste manual foi removido por simplificação. A estratégia passou a Insomnia para contrato isolado TEST, Mockoon para dependências, Billing pela API administrativa e PostgreSQL local. Testcontainers/HTTP loopback continuam na automação; cenários de integração foram preservados em VerifiedCheckoutIT. Validar provedor isolado não comprova persistência/Outbox do Billing.

Cloudflare Tunnel foi considerado transporte HTTPS temporário, sem autenticar notificações/funcionários. HMAC e live_mode=false permanecem obrigatórios; status do body não substitui GET Orders. Não há configuração versionada que comprove isolamento administrativo/interno no túnel ou AWS. A consulta OS sem autenticação permanece opt-in local/loopback separado.

Na observação inicial, order sem vínculo era reconhecida sem recuperação. A implementação posterior passou a **503 vazio**, sem criar dados ou consultar provedor, para permitir reentrega após associação. Não há Inbox/recebimento durável nem garantia de reentrega; a mitigação não resolve order perdida.

## Alternativas consideradas

- **Aceitar prefixo/flag como prova de TEST:** rejeitada.
- **Tratar /users/me como certificado sandbox:** rejeitada; prova titularidade.
- **Conferir titular sem cache a cada candidato/consulta:** escolhida, com custo de latência/disponibilidade.
- **Cache/renovação automática:** excluídos por ampliar estado e política de confiança.
- **Executável alternativo ou bypass de OS/autenticação:** removido/rejeitado; preservado fluxo normal da aplicação.
- **Apagar reserva/usar nova chave após incerteza:** rejeitado; identidade original é necessária à investigação.
- **Habilitar EKS nesta decisão:** rejeitado.

## Consequências

Outro titular é bloqueado antes da reserva; configuração padrão não faz chamadas e falhas anteriores não deixam novas pendências financeiras. Conferência humana do vendedor continua parte da confiança. Cada candidato pode adicionar GET, inclusive concorrentes que não criam order. Testes simulados não certificam aceitação pelo provedor, controles da plataforma ou homologação da jornada completa. Contratos OS/eventos e recuperação permanecem em [débitos](../technical-debt.md).

## Referências

- [ADR 0009](0009-checkout-pro-orders-test-only.md), [ADR 0010](0010-durable-checkout-reservation.md), [ADR 0011](0011-integral-payment-confirmation-and-outbox.md).
- [Integração atual](../integrations/mercado-pago.md), [execução local](../operations/local-testing.md).
- [Credenciais Checkout Pro Orders](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/resources/credentials?scope=prod).
- [Contas de teste](https://www.mercadopago.com.br/developers/pt/docs/checkout-pro-orders/resources/your-integrations/test/accounts).
