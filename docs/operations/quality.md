# Qualidade, testes e Sonar

## Ferramentas do repositório

O [POM](../../pom.xml) configura Surefire (`*Test`), Failsafe (`*IT`), Mockito como agente explícito e JaCoCo 0.8.14 com agentes separados, merge e relatório em `target/site/jacoco-merged/`. SonarScanner Maven 4.0.0.4121 usa projeto `numberone-billing-service` e importa XML consolidado e relatórios dos dois executores. Não há exclusões adicionais de cobertura/análise no POM. Nenhum Checkstyle/PMD/SpotBugs está configurado nele.

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

## Integração contínua

[CI](../../.github/workflows/ci.yml) executa `./mvnw --batch-mode clean verify` com Java 25 em pushes para `feature/**` e `develop`, e em PRs destinados a `develop`. O runner Ubuntu fornece Docker para os Testcontainers, sem Compose ou serviços adicionais. Após o merge dos dados unitários e de integração e a geração do relatório, `jacoco:check` lê `target/jacoco-merged.exec` e exige `LINE/COVEREDRATIO >= 0.80` no bundle, sem exclusões. É o mesmo conjunto de dados do XML consolidado; não há necessidade de outro plugin ou parser. A verificação também vale para `./mvnw verify` local.

O [Dockerfile](../../Dockerfile) copia o JAR já validado, sem executar Maven novamente. Para construir localmente: `./mvnw verify` e `docker build -t numberone-billing-service:local .`. A imagem não é publicada. Os relatórios Surefire, Failsafe e JaCoCo são enviados como artifact mesmo quando uma etapa falha. Sonar permanece exclusivamente local.

O fluxo é `feature/**` → PR automático para `develop` → revisão e CI → **merge manual** → CI em `develop`. O [workflow de abertura](../../.github/workflows/branch-flow.yml) usa somente `GITHUB_TOKEN`, consulta PR aberto antes de criar e não cria PR se não houver commits novos. Não aprova, faz merge ou exclui branches. Diferentemente do legado, não há promoção para `main` nem validação/deploy Kubernetes; a restrição de origem de PR para `main` não se aplica ao escopo do Billing.

Push e PR da mesma branch compartilham o grupo de concorrência do CI, cancelando execuções sobrepostas. Cada execução roda a suíte uma única vez. O CI de PR valida o commit de merge produzido pelo GitHub; se iniciado depois do término do CI do push (por exemplo, após aprovação manual), é uma validação adicional desse merge, que pode incluir mudanças em `develop`.

Configuração necessária no GitHub (não aplicada automaticamente):

1. Manter a branch `develop` existente e habilitar Actions, permitindo `actions/checkout`, `actions/setup-java` e `actions/upload-artifact`.
2. Em **Settings → Actions → General → Workflow permissions**, habilitar **Allow GitHub Actions to create and approve pull requests**. Apesar do nome da opção, o workflow só cria PRs; as permissões de escrita ficam restritas a `pull-requests`. Políticas da organização podem impedir essa configuração.
3. PRs criados/atualizados com `GITHUB_TOKEN` podem gerar execuções de `pull_request` aguardando aprovação. Quando aparecer o banner **Approve workflows to run**, uma pessoa com escrita deve autorizá-las. O CI de `push` roda independentemente disso. Ver [documentação de gatilhos](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow) e [permissões do repositório](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository).
4. Para exigir revisão/CI e impedir integração direta, configurar proteção/ruleset de `develop`: exigir PR, aprovação humana e o check **Validação obrigatória**, com atualização em relação à base antes do merge; restringir bypass conforme a equipe. Desabilitar **Allow auto-merge** e **Automatically delete head branches** nas configurações gerais do repositório para manter o fluxo exclusivamente manual.

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

