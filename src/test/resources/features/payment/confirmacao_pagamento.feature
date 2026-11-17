# language: pt
Funcionalidade: Jornada de orçamento e cobrança
  O cliente conhece o valor calculado antes de aprovar e pagar sua ordem de serviço.

  Contexto:
    Dado que uma OS em diagnóstico possui um serviço de R$ 150,00 e duas unidades de material de R$ 25,00

  @payment
  Cenário: Pagamento do orçamento aprovado com entrega repetida do webhook
    Quando o atendente cria o orçamento sem informar um valor manual
    E o atendente solicita a aprovação do orçamento
    E o cliente abre o link público e confirma a aprovação
    Então o orçamento de R$ 200,00 fica aprovado remotamente e a autorização é consumida
    Quando a OS é concluída e o atendente cria o checkout
    E o provedor confirma os R$ 200,00 e entrega o mesmo webhook assinado duas vezes
    Então a cobrança fica quitada e somente um evento PagamentoConfirmado é persistido
