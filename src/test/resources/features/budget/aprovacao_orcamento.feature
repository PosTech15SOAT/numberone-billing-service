# language: pt
Funcionalidade: Jornada de orçamento e cobrança
  O cliente conhece o valor calculado antes de aprovar e pagar sua ordem de serviço.

  Contexto:
    Dado que uma OS em diagnóstico possui um serviço de R$ 150,00 e duas unidades de material de R$ 25,00

  @budget
  Cenário: Aprovação pública de um orçamento calculado com serviços e materiais
    Quando o atendente cria o orçamento sem informar um valor manual
    E o atendente solicita a aprovação do orçamento
    E o cliente abre o link público e confirma a aprovação
    Então o orçamento de R$ 200,00 fica aprovado remotamente e a autorização é consumida
    E a outbox registra o envio e a aprovação com o valor e a identidade do orçamento

