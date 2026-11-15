# ADR 0007: Autorizações temporárias e notificações de orçamento

**Status:** Aceita

**Data:** 2026-11-06

**Complemento:** 2026-11-06 — página pública e consumo transacional da autorização.

## Contexto

O envio administrativo de uma revisão já confirma SENT e OrcamentoEnviado no PostgreSQL. O funcionário precisa solicitar a comunicação ao cliente e acompanhar falhas sem confundir o estado comercial com o resultado do SMTP. Uma indisponibilidade do provedor SMTP não deve manter uma transação de orçamento aberta nem perder a intenção de notificar após reinício.

A decisão remota usa uma autorização temporária associada a um budgetId, que identifica uma revisão comercial. O processamento assíncrono precisa conservar o link até a entrega ao SMTP, mas o token original não pode ficar em texto puro no banco. A Outbox SQS guarda fatos de integração para a OS e não deve transportar links ou dados pessoais do e-mail.

## Decisão

No primeiro envio, persistir SENT, OrcamentoEnviado, autorização e notificação PENDING na mesma transação local. A V7 cria `budget_approval_authorization` e `budget_email_notification`. A primeira mantém identidade da autorização, revisão, hash, criação, expiração e revogação; a segunda mantém a solicitação de entrega, conteúdo protegido, tentativas, agendamento e posse. A chave estrangeira composta impede associar uma notificação à autorização de outra revisão. Não há nova fila ou tabela genérica de mensagens.

O UseCase obtém o e-mail no contexto de orçamento da OS antes da transação. O gateway relê Budget e a vigência sob o lock existente do contador da OS. A transação conserva valores, snapshot e budgetVersion e não chama SMTP. Uma falha em qualquer uma das quatro gravações reverte todas. Sem chave de proteção e URL configuradas, a operação falha com 503 e não confirma SENT.

### Autorização e proteção do link

Gerar 32 bytes com `SecureRandom`, codificar em Base64 URL sem padding e guardar somente SHA-256 na autorização. A alta entropia do token permite usar hash direto; não se trata de uma senha escolhida pelo usuário. A validade inicial é 48 horas, configurável. Expirar a autorização não altera SENT e não exige job. Um índice parcial admite somente uma autorização não revogada e não consumida por revisão; o reenvio revoga a anterior, inclusive se já expirou.

O conteúdo necessário à entrega, incluindo destinatário e link, fica cifrado com AES-256-GCM, nonce aleatório de 12 bytes, tag de 128 bits e authorizationId como dado autenticado adicional. O formato protegido possui versão. A chave Base64 vem de `BUDGET_NOTIFICATION_ENCRYPTION_KEY`, externa ao banco e igual em todas as réplicas. Ela não possui valor padrão de produção. Hash para validação e conteúdo cifrado para entrega têm funções separadas.

Tokens não integram respostas administrativas, erros, logs ou eventos SQS. Objetos de conteúdo protegido e configuração não exibem seus segredos em `toString`. Conteúdo cifrado é removido quando a notificação termina SENT, FAILED ou CANCELLED; hash e histórico operacional permanecem. A aplicação comprometida junto com a chave ainda pode decifrar pendências; criptografia no banco não substitui proteção do processo e do segredo.

`BUDGET_APPROVAL_BASE_URL` recebe a URL completa da página do próprio Billing, `/api/public/orcamentos-ordem-servico/decisao`, sem query, fragmento ou credenciais. O link acrescenta `?token=...`. HTTPS é obrigatório, com exceção de HTTP em localhost/127.0.0.1 para desenvolvimento. O verificador interno de hash permanece apenas uma consulta; não autoriza uma escrita a partir de uma leitura anterior.

### Página e decisão públicas

O GET renderiza HTML responsivo, em português, sem consumir a autorização ou alterar Budget. A página apresenta identificação, revisão, valor proposto e validade. A composição é exibida com os dados registrados, sem inventar nomes de serviços, recalcular valores, assumir moeda global ou substituir informação ausente por zero. Não mostra e-mail, identidade de funcionário, identificadores técnicos de itens, hashes ou metadados de persistência.

POSTs separados em `/api/public/orcamentos-ordem-servico/aprovar` e `/rejeitar` recebem token no formulário e `confirmacao=sim`. Cada opção possui caixa de confirmação obrigatória e botão explícito. Não há decisão via GET ou redirecionamento com a credencial. A posse do token é a autorização pública; não é adicionada sessão de login, JWT ou identidade autenticada fictícia do cliente. Como não há autenticação por cookie, o formulário usa a própria credencial não ambiental, sem um segundo token de sessão.

A infraestrutura identifica a revisão pelo hash e usa a mesma transação e lock por OS das decisões administrativas. Depois da espera pelo lock, recarrega Budget e autorização, verifica SENT vigente, ausência de aprovação impeditiva, expiração, revogação e consumo. A atualização condicional de consumo verifica novamente o prazo no relógio PostgreSQL. O domínio aplica a transição e mantém @Version apenas na entidade JPA.

A V8 acrescenta `consumed_at`, sem apagar autorizações anteriores. O índice de autorização ativa passa a excluir consumidas; a constraint impede consumo expirado ou simultâneo à revogação. Uma autorização consumida conserva o vínculo com a revisão e não recebe revogação posterior. Consumo, decisão, datas, auditoria, cancelamento das notificações pendentes e OrcamentoAprovado/OrcamentoRejeitado participam do mesmo commit ou rollback. Não se gera outro OrcamentoEnviado.

Aprovação define `approval_origin=REMOTE`, valor aprovado integral e approvedAt, sem funcionário. Rejeição preserva a semântica existente de updatedAt; o campo approval_origin e sua constraint são exclusivos de aprovações. O consumo vinculado ao Budget REJECTED registra o canal remoto sem introduzir outro campo no domínio. Os endpoints administrativos legados mantêm seu comportamento e origem histórica desconhecida.

Exemplo: duas abas abrem a mesma revisão SENT. Aprovar na primeira consome a autorização e confirma OrcamentoAprovado. Rejeitar na segunda, mesmo com GET anterior válido, relê o consumo sob o lock e não registra OrcamentoRejeitado. A mesma serialização coordena aprovação presencial, reenvio e envio de revisão mais nova. Não há lock HTTP ou outro mecanismo de concorrência.

Respostas de erro mostram apenas motivo e orientação: link inválido 404, expirado/revogado 410, consumido/decidido/obsoleto ou conflito otimista 409, confirmação ausente 400 e falha inesperada 500. Não mostram dados comerciais ou token. Sucesso retorna confirmação HTML, sem token, com HTTP 200. O formulário válido contém a credencial em input oculto, necessário ao POST; por isso toda a página é privada e não armazenável em cache.

Usar no-store, Referrer-Policy no-referrer, bloqueio de frames, nosniff e CSP restritiva. A página usa templates Thymeleaf e CSS local em `/css/budget.css`, com `style-src 'self'`; não carrega scripts, fontes, imagens ou outros recursos externos. Conteúdo variável é escapado. Um filtro inicial remove query, parâmetros e body da visão do MVC, após leitura para um objeto com toString protegido. Esse objeto permanece no modelo da view, sem uma cópia do token em String que apareceria nos logs do modelo. A renderização não usa conversores de corpo HTTP; falhas inesperadas dos UseCases recebem tratamento local sem registrar a mensagem original. Testes em TRACE verificam a ausência da credencial. O access log Tomcat fica desabilitado. Logs de Gateway, ingress e APM ainda dependem da configuração da plataforma para omitir query/body; esta entrega não altera esses componentes. O e-mail e a URL inicial no navegador continuam contendo uma credencial, sem promessa de removê-la do histórico do cliente.

### Reenvio e invalidação

`POST /api/admin/orcamentos-ordem-servico/{id}/reenviar-aprovacao` aceita somente a revisão vigente em SENT. Pode ser solicitado antes ou depois da expiração. Consulta o e-mail atual pelo contexto de orçamento da OS, sem recalcular preços ou exigir novamente o estado técnico de criação da OS. Sob o mesmo lock por OS, verifica um intervalo mínimo entre solicitações, revoga autorizações anteriores e cria nova autorização/notificação atomicamente.

O intervalo inicial de 30 segundos é uma proteção operacional configurável contra cliques repetidos. Uma solicitação dentro dele retorna 409, inclusive depois de um envio inicial. Não é idempotência HTTP: após o intervalo, outra solicitação válida representa um novo envio. O reenvio não altera Budget, row_version, sentAt ou budgetVersion e não gera OrcamentoEnviado.

Uma decisão comercial revoga as autorizações ainda não consumidas da OS e cancela notificações pendentes. A decisão pública consome sua autorização e preserva essa evidência. Enviar uma revisão mais nova também invalida os links anteriores. Criar apenas outro DRAFT preserva o link da revisão SENT vigente. A aprovação presencial de DRAFT continua sem autorização, e-mail ou OrcamentoEnviado.

### Processamento e estados

Usar um processador Spring periódico independente do publicador SQS. Ele reutiliza os conceitos já testados na Outbox: reivindicação limitada com `FOR UPDATE SKIP LOCKED`, token e prazo de posse, transação curta e confirmação condicionada à posse válida. Não extrair um processador genérico: e-mail tem autorização, revogação e conteúdo cifrado próprios.

| Estado armazenado | Significado |
| --- | --- |
| PENDING | Aguarda tentativa, backoff ou conclusão de uma posse ativa. |
| SENT | O servidor SMTP aceitou o envio; não comprova entrega ao destinatário. |
| FAILED | Tentativas esgotadas ou falha que exige correção antes de outro envio. |
| CANCELLED | Autorização expirada, revogada, decidida ou revisão obsoleta antes de concluir a entrega. |

Não armazenar PROCESSING: a posse já distingue um registro reivindicado. A reivindicação incrementa tentativas e registra a última tentativa, inclusive se o processo cair antes do SMTP. O banco fornece os horários operacionais. Após expirar a posse, outra réplica pode retomar. Se a última tentativa caiu, a recuperação encerra em FAILED, evitando retomadas infinitas.

Antes do SMTP, verificar novamente posse, autorização, expiração, estado SENT e referência comercial. Decifrar e enviar fora da transação. Falhas transitórias usam backoff exponencial limitado; autenticação, preparação de mensagem ou conteúdo indisponível encerram em FAILED. Outros erros SMTP, inclusive rejeições não classificadas, seguem o limite de tentativas. Persistir somente categorias controladas, sem senha, endereço, link ou resposta técnica arbitrária. Não reabrir automaticamente notificações finais; o funcionário solicita um novo envio, com novo token.

Usar Spring Mail gerenciado pelo Spring Boot para provedor SMTP SMTP, com host, porta, usuário, senha e remetente externos. O padrão é porta 587 com autenticação e STARTTLS obrigatório, debug desabilitado e limites de conexão, leitura e escrita. O processador fica desabilitado por padrão, inclusive localmente. Habilitá-lo exige configuração válida de chave, URL, remetente e credenciais. Desabilitar o processador conserva as solicitações no banco; não elimina sua criação transacional no envio.

### Consulta administrativa

Estender somente o GET individual com `notificacao`, preservando os nove campos legados. O UseCase consulta separadamente Budget e a solicitação mais recente por criação; não exige JOIN nem carrega notificações no histórico de revisões. Sem solicitação, retorna `notificacao: null`, inclusive para aprovação presencial.

O resumo usa status em português, tentativas, última tentativa, data de aceite SMTP e `podeReenviar`. A indicação combina SENT vigente e intervalo mínimo decorrido. É informativa: e-mail e condições de escrita são revalidados no comando. Nenhum segredo ou diagnóstico interno integra esse objeto.

## Alternativas consideradas

- **SMTP síncrono durante o envio comercial:** descartado porque manteria o lock da OS durante a comunicação e poderia enviar e-mail de uma transação posteriormente revertida.
- **Executor em memória após o commit:** perderia a solicitação na queda do processo e não coordenaria réplicas. Descartado.
- **Usar a Outbox SQS para e-mail:** misturaria fatos para a OS com dados privados e o ciclo de uma autorização revogável. As responsabilidades permanecem separadas.
- **Nova fila de notificações:** exigiria outra infraestrutura sem eliminar a gravação transacional da intenção. O PostgreSQL existente atende ao volume ainda não dimensionado do MVP; sua capacidade precisa ser acompanhada.
- **Token em texto puro ou reversível com chave no banco:** exporia o acesso remoto ao leitor da base. Escolhido hash para validação e conteúdo de entrega cifrado com chave externa.
- **Somente hash e geração de outro token no worker:** o link não poderia ser reconstruído e seria necessário alterar a autorização já confirmada a cada tentativa. A cifra conserva o mesmo link durante as retentativas.
- **Expirar comercialmente o orçamento com o token:** descartado. A autorização expira; orçamento sem resposta continua SENT e admite reenvio.

## Consequências

### Positivas

- Commit comercial válido deixa a autorização e a intenção de comunicação duráveis, mesmo com SMTP indisponível.
- Reenvio e decisões respeitam a serialização por OS e preservam histórico, snapshots e contratos SQS.
- O funcionário acompanha a última solicitação sem interpretar SENT comercial como comprovante de entrega.
- Não há nova fila, consumidor ou framework em domain/application. Os testes usam PostgreSQL descartável e SMTP restrito a loopback.

### Negativas e trade-offs

- SMTP não participa da transação PostgreSQL. Aceite seguido de queda antes da confirmação pode causar envio duplicado do mesmo link. Não há promessa de entrega exatamente uma vez.
- Um reenvio pode revogar o link depois da checagem e enquanto o SMTP já está em andamento. O e-mail antigo ainda pode chegar; a validação pública recusa o token revogado. Não se mantém lock de OS durante o SMTP para tentar fechar essa janela.
- Expiração da posse durante SMTP pode permitir outra tentativa concorrente. Timeouts de socket não constituem limite absoluto da conversa SMTP; lote e posse devem ser dimensionados e monitorados. A posse protege a confirmação local, sem desfazer um aceite externo.
- A chave precisa estar disponível, protegida e consistente nas réplicas. Não há keyring ou rotação automática nesta etapa. Antes de trocar a chave, planejar o tratamento das pendências e a recuperação do segredo; perdê-la exige novo envio, não permite recuperar os tokens a partir dos hashes.
- O provedor SMTP e a conta utilizada precisam permitir autenticação SMTP; credenciais ou remetente incorretos exigem correção operacional. Aceite não garante caixa de entrada, leitura ou decisão do cliente. Não foram enviados e-mails reais nos testes.
- A página pública está implementada, mas execução local de ponta a ponta, provedor SMTP real e configuração de encaminhamento/logs na plataforma dependem de validação operacional externa. Links cifrados com destino provisório não são reescritos; após configurar a URL correta, usar reenvio.
- Tabelas, índice e polling consomem o mesmo PostgreSQL do Billing. Retenção do histórico e parâmetros definitivos de produção continuam pendentes, sem limpeza destrutiva automática.

## Referências

- [ADR 0004: Transactional Outbox](0004-transactional-outbox.md).
- [ADR 0006: Autenticação centralizada](0006-centralized-authentication.md).
- [Contratos HTTP](../integrations/budget-http-contracts.md).
- [Notificações e execução local](../flows/budget-approval.md).
- [Migration V7](../../src/main/resources/db/migration/V7__create_budget_approval_notifications.sql).

## Evolução da configuração SMTP

A decisão inicial citava um provedor específico. O adaptador atual usa host/porta/credenciais/remetente externos; `.env.example` exemplifica Brevo. O e-mail é texto simples com SimpleMailMessage. Thymeleaf renderiza somente as páginas públicas. Integração com SMTP real e proteção do encaminhamento são verificações operacionais separadas dos testes simulados.
