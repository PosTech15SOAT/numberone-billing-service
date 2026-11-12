# Glossário inglês–português

| Termo | Tradução e significado no Billing |
| --- | --- |
| Budget | Orçamento; raiz que representa uma revisão comercial |
| Budget Revision | Revisão de orçamento; outro `Budget`, com UUID próprio e `budgetVersion` crescente por OS |
| Commercial snapshot | Composição comercial registrada em JSONB; não é recalculada no envio/decisão |
| Current revision | Revisão de referência: maior versão entre SENT, APPROVED e REJECTED |
| Service Order / OS | Ordem de serviço; pertence ao OS Service e é referenciada por UUID |
| Charge | Cobrança; raiz financeira com valor do orçamento aprovado e histórico de tentativas |
| PaymentAttempt | Tentativa de pagamento; entidade interna de Charge, com chave idempotente própria |
| PaymentMethod | Meio de pagamento; enum PIX/CARD, inicialmente ausente |
| PaymentProvider | Provedor de pagamento; Mercado Pago nesta integração, sem entidade ou enum local com esse nome |
| Payer | Pagador no provedor; não existe cadastro/agregado local com esse nome |
| Order | Ordem do provedor que disponibiliza o checkout; diferente de OS e de transação financeira |
| Checkout | Página hospedada pelo provedor para o cliente escolher e efetuar o pagamento |
| Outbox | Tabela de intenções de publicação persistidas junto com a transição comercial |
| Inbox | Registro de recebimento/deduplicação esperado no consumidor externo; não implementado no Billing |
| Claim / lease | Reivindicação e prazo temporário de posse dos workers SMTP/SQS |
| Checkout reservation | Reserva permanente de criação externa; não usa a expiração de um lease |
| Idempotency key | Chave UUID que identifica a tentativa; enviada ao provedor como `X-Idempotency-Key` |
| Webhook | Notificação HTTP recebida do provedor; provoca consulta, não fornece evidência financeira confiável por si |
| HMAC | Autenticação de mensagem com segredo compartilhado; valida a notificação |
| TEST | Ambiente financeiro permitido pela configuração local; prefixo do token não certifica sua origem |
| ECS | Elastic Common Schema, formato JSON dos logs; não é Amazon ECS |
| SQS / DLQ | Fila AWS / fila de mensagens não processadas conforme política de redrive |
| Saga | Coordenação de passos entre serviços; não há coordenador/compensação implementado neste serviço |
| DDD | Modelagem por domínio com agregados e invariantes; os domínios Budget e Payment vivem no mesmo serviço |
| PK / FK | Chave primária / estrangeira física; UUID externo não implica FK |

| Estado ou evento | Significado |
| --- | --- |
| DRAFT / RASCUNHO | Orçamento criado, ainda não enviado |
| SENT / ENVIADO | Orçamento disponibilizado para decisão, independente de aceite SMTP |
| APPROVED / APROVADO | Orçamento aprovado integralmente |
| REJECTED / REJEITADO | Orçamento rejeitado |
| CANCELLED / CANCELADO | Estado de orçamento representável sem comando atual de cancelamento |
| IN_PERSON / REMOTE | Origem presencial/remota da aprovação; legado pode ter origem nula |
| OPEN / PAID | Cobrança aberta/quitada |
| PENDING / UNKNOWN | Tentativa pendente/resultado desconhecido; ambas bloqueiam nova operação |
| FAILED / EXPIRED | Tentativa encerrada por falha/expiração confirmadas; webhook atual não produz essas transições |
| NEEDS_REVIEW | Evento Outbox que exige intervenção; não é falha da DLQ |
| PENDING / SENT / FAILED / CANCELLED da notificação | Aguardando, aceita pelo SMTP, falha final ou cancelada; API traduz para PENDENTE/ENVIADA/FALHA/CANCELADA |
| APPROVED da observação | Evidência financeira apta à revalidação; não equivale, isoladamente, a PAID persistido |
| OrcamentoEnviado / OrcamentoAprovado / OrcamentoRejeitado | Fatos de revisão enviados pela Outbox |
| PagamentoConfirmado | Primeira quitação comercial da cobrança; `paymentId` é `chargeId` |

Detalhes de [domínio](domain-model.md) e [contratos dos eventos](../integrations/events.md).
