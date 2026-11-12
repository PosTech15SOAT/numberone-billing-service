# ADR 0013: Validação HMAC pelo SDK oficial Mercado Pago

**Status:** Aceita — compatibilidade de caixa vigente; instrumentação temporária removida.

**Data:** 2026-11-10

## Contexto

O histórico da integração registra SDK 3.7.0 preservando caixa de data.id após PR #372, simulação validando com ID original e duas entregas automáticas TEST que validaram offline somente após conversão para minúsculas com o mesmo segredo. A orientação de suporte sobre lowercase divergia do comportamento original do SDK. A evidência registrada motivou compatibilidade localizada, sem comprovar canonicalização universal ou substituir homologação da jornada.

## Decisão

Usar `com.mercadopago:sdk-java:3.7.0` somente para `WebhookSignatureValidator.validate`. `MercadoPagoSignatureValidator` conserva guardas de identificadores, headers únicos, ts numérico, v1 hexadecimal e componentes inequívocos; traduz exceções sem anexar componentes privados da requisição. Algoritmo/comparação ficam no validador do SDK, sem token, HTTP ou estado global de credenciais.

Tentar ORIGINAL primeiro. Apenas SIGNATURE_MISMATCH, ID exclusivamente alfanumérico ASCII e mudança de caixa permitem segunda tentativa com Locale.ROOT. Sucesso retorna LOWERCASE_COMPATIBILITY; outros erros, IDs numéricos/já minúsculos ou com hífen/underscore não acionam alternativa. Não escolher por IP, User-Agent, Referer, body ou parâmetro adicional. Identidade original permanece no body, domínio, persistência e GET financeiro; só o argumento da assinatura é canonicalizado.

PAYMENT_WEBHOOK_SIGNATURE_VERIFIED (DEBUG) registra estratégia, resultado, ambiente e ID original; PAYMENT_WEBHOOK_SIGNATURE_COMPATIBILITY_APPLIED (INFO) ocorre somente no sucesso alternativo. Não registrar digest, manifesto, assinatura ou segredo.

Excluir transitivas do SDK, pois o validador e exceções usados dependem do JDK; os demais clientes/modelos não são utilizados. Reavaliar exclusões e compatibilidade ao atualizar/adotar outro módulo. Preservar a política temporal existente, sem acrescentar janela de expiração a ts. Idempotência e consulta financeira continuam necessárias contra repetição.

## Histórico da investigação e remoção

Houve instrumentação temporária opt-in local/DEBUG, com snapshots A/B restritos à requisição e reprodução posterior pelo SDK sem participar da decisão HTTP. Uma exceção investigativa também permitiu captura bruta de componentes Servlet, com limite de 64 KiB e sem o segredo configurado. Isso tinha risco de exposição de dados recebidos e não integrava o padrão permanente de logging.

A revisão posterior removeu captura/cache, reprodução A/B, utilitário, testes exclusivos e propriedade/flag. O binding estrito atual não aceita `billing.payment.mercado-pago-webhook.signature-diagnostics-enabled`. Esses mecanismos não estão disponíveis nem autorizados como procedimento atual. Permaneceram SDK, guardas, fallback restrito e eventos operacionais sanitizados. A descrição antiga de um TODO específico de revisão também não corresponde a um comentário no código atual; o acompanhamento está no [registro de débitos](../technical-debt.md#canonicalização-hmac).

## Alternativas consideradas

- **Criptografia própria:** funcionava, mas exigia manter algoritmo/canonicalização do provedor.
- **Migrar transporte para SDK:** fora de escopo; acrescentaria dependências/comportamento sem necessidade.
- **Lowercase incondicional:** rejeitada por quebrar a modalidade original registrada.
- **Aceitar duas canonicalizações:** inicialmente rejeitada sem evidência; depois aprovada sob condições restritas após os registros automáticos validarem somente com lowercase.
- **Manter captura investigativa:** removida ao encerrar sua finalidade temporária; não integra a observabilidade permanente.

## Consequências

Reduz manutenção criptográfica e preserva HTTP/guardas locais, com dependência de um artefato completo para pequena função. A alternativa amplia o conjunto autenticado: a caixa exata não é autenticada na estratégia lowercase; uma assinatura pode se aplicar a variantes com a mesma forma minúscula. Não se presume equivalência de identidade entre essas orders. Consulta autenticada mantém ID original e verifica referência/valores antes da quitação.

Esclarecimento do emissor/SDK e eventual unicidade dos IDs por caixa continuam pendentes. Remover a alternativa exige canonicalização oficial aplicável ao produto, testes de ambas as modalidades e evidência de notificações reais; nenhuma atualização automática de SDK ou fallback de segredo é inferida desta decisão.

## Referências

- [Contrato implementado](../integrations/mercado-pago.md), [observabilidade](../operations/observability.md).
- [Validador SDK 3.7.0](https://github.com/mercadopago/sdk-java/blob/3.7.0/src/main/java/com/mercadopago/webhook/WebhookSignatureValidator.java).
- [PR #372](https://github.com/mercadopago/sdk-java/pull/372).
