# Qualidade, testes e Sonar

## Ferramentas do repositório

O [POM](../../pom.xml) configura Surefire e Failsafe (seleção detalhada abaixo), Mockito como agente explícito e JaCoCo 0.8.14 com agentes separados, merge e relatório em `target/site/jacoco-merged/`. SonarScanner Maven 4.0.0.4121 usa projeto `numberone-billing-service` e importa XML consolidado e relatórios unitários, de integração e BDD. Não há exclusões adicionais de cobertura/análise no POM. Nenhum Checkstyle/PMD/SpotBugs está configurado nele.

[Compose Sonar](../../docker-compose.sonar.yml) usa Community Build fixada por digest, porta loopback 9000, volumes exclusivos, H2 e bootstrap checks desabilitados para uso local. Não é configuração de produção.

```bash
scripts/sonar-up.sh
read -rsp 'Token Sonar local: ' SONAR_TOKEN
printf '\n'
export SONAR_TOKEN
scripts/sonar-scan.sh
unset SONAR_TOKEN
scripts/sonar-down.sh
```

No primeiro acesso, configure a conta local e gere token no servidor. `SONAR_PORT` altera a porta; `MVN_CMD` escolhe executável Maven. O scan verifica servidor local UP, executa `clean verify sonar:sonar` e aguarda Quality Gate (até 300s). Os scripts de parada preservam volumes. Build/testes sem publicação: `./mvnw verify`.

## Organização e execução dos testes

As classes ficam em `src/test/java/br/com/fiap/numberone/billing`, agrupadas por domínio antes da categoria:

| Pacote | Responsabilidade | Seleção Maven | Relatórios / cobertura |
| --- | --- | --- | --- |
| `budget/unit`, `payment/unit` | Regras isoladas de domínio e aplicação, com JUnit/Mockito | Surefire: `unit/**/*Test` | `target/surefire-reports`, `target/jacoco-unit.exec` |
| `budget/integration`, `payment/integration`, `shared/integration` | APIs, adaptadores, contratos, configuração Spring, observabilidade e persistência | Failsafe: `integration/**/*Test` e `integration/**/*IT` | `target/failsafe-reports`, `target/jacoco-integration.exec` |
| `budget/e2e`, `payment/e2e`, `shared/support/e2e` | Steps de domínio, runner e infraestrutura BDD compartilhada | Failsafe: `e2e/**/*CucumberTest` | `target/bdd-reports`, `target/cucumber-reports`, `target/jacoco-bdd.exec` |
| `payment/support`, `shared/support` | Fixtures e utilitários compartilhados; infraestrutura controlada do BDD | Sem execução direta | — |

A exceção `payment.domain.entities.CheckoutReservationTest` permanece no pacote de produção para acessar operação package-private e é selecionada explicitamente como unitária. A antiga guarda package-private de ambiente foi removida junto com seu teste; os testes de configuração exercitam a ativação em Kubernetes. `SimulatedSmtpServer` fica junto ao consumidor, pois seus membros são package-private.

```bash
# Somente unitários
./mvnw clean test
# Somente integração
./mvnw clean verify -DskipUnitTests=true -DskipBddTests=true -DskipMergedCoverage=true
# Somente BDD
./mvnw clean verify -DskipUnitTests=true -DskipIntegrationTests=true -DskipMergedCoverage=true
# Todos os grupos, JAR e cobertura consolidada mínima de 80%
./mvnw clean verify
```

Use Java 25 e Docker disponível para integração e BDD. A persistência utiliza PostgreSQL 17 Testcontainers; `MockoonEnvironmentIT` também inicia Mockoon. Muitos testes de adaptadores e HTTP não usam containers, mas pertencem à integração pela responsabilidade. `skipMergedCoverage` evita validar dados parciais nos comandos isolados; o comando completo e o job obrigatório não desativam a verificação. O comando anterior de integração recebe agora `-DskipBddTests=true`, pois `verify` também inclui BDD por padrão. Não há profile Maven novo. Use `clean` para evitar classes e dados de cobertura de execuções anteriores.

## BDD/Cucumber

As features em português ficam em `src/test/resources/features/budget/aprovacao_orcamento.feature` e `src/test/resources/features/payment/confirmacao_pagamento.feature`. `BillingCucumberTest`, em `shared/support/e2e`, seleciona o diretório de features e os glues de `budget.e2e`, `payment.e2e` e `shared.support.e2e`. O cenário Payment reutiliza os steps de aprovação de Budget. Para executar um cenário isolado, acrescente `-Dcucumber.filter.tags=@budget` ou `-Dcucumber.filter.tags=@payment` ao comando BDD acima. Cucumber 7.34.3 executa pela JUnit Platform Suite; Surefire e a execução Failsafe de integração selecionam apenas Jupiter. A descoberta direta do engine Cucumber está desativada em `junit-platform.properties` para impedir a execução duplicada fora da suite. A configuração segue a [documentação do engine Cucumber](https://github.com/cucumber/cucumber-jvm/blob/v7.34.3/cucumber-junit-platform-engine/README.md).

O cenário de orçamento cria uma cotação calculada de R$ 200,00 (serviço de R$ 150,00 e dois materiais de R$ 25,00), solicita aprovação, recupera o link do conteúdo protegido persistido, abre a página pública e confirma. Verifica valor, estado, origem remota, consumo da autorização, cancelamento da notificação e eventos na outbox com identidade e valor corretos.

O cenário de pagamento parte da mesma aprovação, conclui a OS controlada, cria checkout e entrega duas vezes o webhook com assinatura válida. Verifica quitação pela evidência financeira e apenas um evento `PagamentoConfirmado`. Não altera status de cobrança por SQL. As portas de checkout e consulta financeira usam doubles; casos de uso, HMAC, HTTP, transações e persistência continuam reais. Isso testa a jornada do Billing, sem certificar contratos ou transporte reais do provedor.

Spring Boot Test inicia o Billing em porta aleatória com PostgreSQL descartável. OS e Inventory respondem em servidor HTTP loopback, mantendo os adaptadores reais. Hooks limpam tabelas, reiniciam doubles e restauram a fixture entre cenários. O profile `local` já existente é usado na fixture; `spring.config.import` vazio evita carregar `.env`, e workers Outbox/SMTP e cliente Mercado Pago permanecem desabilitados. Não há AWS, SMTP ou provedor financeiro reais. Relatórios Cucumber HTML, JSON e JUnit ficam em `target/cucumber-reports`; Failsafe mantém resumo próprio em `target/bdd-reports`.

## Cobertura e integração contínua

`./mvnw clean verify` executa os três grupos uma vez. JaCoCo combina `jacoco-unit.exec`, `jacoco-integration.exec` e `jacoco-bdd.exec` em `target/jacoco-merged.exec`, usando os probes reais e as mesmas classes instrumentadas; não soma percentuais nem calcula médias. O relatório HTML/XML fica em `target/site/jacoco-merged/`. Na execução local desta alteração, foram cobertas 2.930 de 3.145 linhas (93,16%); 395 testes unitários, 991 de integração e dois cenários BDD passaram sem falhas ou ignorados. `jacoco:check` exige `LINE/COVEREDRATIO >= 0.80` no serviço, sem exclusões. O Sonar local importa esse XML e os relatórios dos três grupos; seu Quality Gate permanece local pelo procedimento acima.

O [CI](../../.github/workflows/ci.yml) executa **Testes unitários**, **Testes de integração** e **BDD — Cucumber** independentemente e em paralelo com Java 25. Cada job publica relatórios e dados JaCoCo. **Validação obrigatória** depende do sucesso dos três e falha explicitamente se algum não passar. Recompila somente as classes, combina os três arquivos baixados e aplica o limite de 80%, sem repetir testes. Depois valida Docker e manifests, incluindo os testes do validador de deploy. Usa o JAR produzido na integração e, quando chamado pelo deploy, publica `billing-application-<SHA>` somente após todas as verificações. O deploy consome esse artifact da mesma execução antes de ECR/EKS. Falhas de qualquer grupo bloqueiam o fluxo; Sonar permanece local.

O Dockerfile copia o JAR validado: `./mvnw clean verify` seguido de `docker build -t numberone-billing-service:local .`. Consulte [CI/CD](../../k8s/CI-CD.md) para gatilhos e configuração de branches. Em rulesets, mantenha **Validação obrigatória** como check obrigatório e confira os nomes efetivos após a mudança, inclusive jobs individuais quando exigidos pela proteção. Para preservar revisão e merge manuais em `develop`, exija PR e aprovação, limite bypass e desabilite auto-merge e exclusão automática de branches.

## Registro histórico de 11/11/2026

Os relatórios anteriores registram 987 testes Surefire + 302 Failsafe (1.289), sem falhas/erros/ignorados, e análise local com Gate PASSED, cobertura global 90,4%, linhas 95,9%, condições 82,2%, 11 alertas classificados BUG, 519 code smells, zero vulnerabilidades/hotspots e duplicação 1,0%. São resultados **históricos reportados**, não execução ou garantia de segurança desta consolidação. Primeira análise tinha comparação de código novo indisponível e 230 arquivos sem blame; não foi criado commit para alterar isso.

O diagnóstico manual anterior tinha apontado ACK de order sem vínculo, captura bruta temporária, classificação genérica de falhas pré-reserva e aceitação de frações reais de centavo. O código atual retorna 503 vazio para order sem vínculo, removeu captura/flag, classifica preparação por erros CHECKOUT_* e valida centavos exatos. Esses pontos não permanecem como defeitos abertos. Recuperação durável, contratos/isolamento externos, escala no mapper e limites financeiros seguem em [débitos técnicos](../technical-debt.md).

## Triagem contextual preservada

A revisão anterior dos 11 BUGs concluiu ausência de defeito funcional demonstrado no contexto implementado, sem alterar seu status no servidor. A classificação histórica é preservada por regra/local, sem depender de identificadores de issues de um servidor pessoal.

| Regra e local | Quantidade | Justificativa da triagem |
| --- | --- | --- |
| S2201: BudgetComposition.hasCentPrecision e Charge.validateNewOperationAmount | 2 | Descartar retorno de setScale é intencional: UNNECESSARY valida por exceção, preservando escala original. Frações reais são recusadas; zeros finais excedentes aceitos. |
| S2259: BudgetNotificationConfig, getters username/password | 2 | Curto-circuito verifica null antes de isBlank; não há mutação concorrente/getter variável no caminho atual. Teste direto de null/blank do bean era lacuna de cobertura, sem NPE demonstrado. |
| S2259: BudgetNotificationRepository.currentTime e schedule | 2 | SELECT statement_timestamp() produz instante não nulo; falha JDBC lança exceção. |
| S2259: BudgetRevisionCounterRepository.nextVersion | 1 | UPSERT sem filtro retorna last_version NOT NULL; erro/overflow faz rollback, não retorno null nesse SQL. |
| S6218: ClaimedBudgetNotification | 1 | Array tem igualdade por identidade no record; consumidores usam IDs/lease/conteúdo explicitamente, sem requisito de igualdade por conteúdo ou uso como chave de deduplicação. |
| S2789: ChargeGatewayImpl.inspectCheckout | 1 | Callback retorna Optional ou lança; verificação result != null é redundante no caminho atual, sem resultado funcional incorreto. |
| S5841: dois cenários concorrentes em ChargePersistenceIT | 2 | Helper retorna exatamente dois resultados de Future.get; timeout/erro impede chegar a asserção, sem passagem por lista vazia. |

S6809 é code smell, não um dos 11 bugs: autoinvocação de métodos MANDATORY em schedule/revoke e append ocorre com transação externa já ativa; não há REQUIRES_NEW/isolamento diferente a perder. Atomicidade é coberta pelos ITs. Isso não autoriza assumir qualquer autoinvocação futura como transacional.

Os 519 smells não receberam triagem completa. Alertas de depreciação/complexidade/estilo não justificam refatoração ampla sem benefício concreto. Configurações/recursos não receberam análise estática adicional naquele scan; contagem de testes/cobertura não certifica isolamento AWS, contratos reais, carga, recuperação ou homologação do provedor.

