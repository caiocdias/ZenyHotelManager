# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projeto

Servidor do Zeny Hotel Manager: PMS e operações de **um único hotel** de até 30 UHs. Este repositório contém **só o servidor**. Os clientes (desktop, mobile, PDV, tela de produção e agente de impressão) ficam em outros repositórios e geram seus SDKs a partir do contrato OpenAPI (`api/openapi.yaml`, gerado; nunca edite à mão).

- `docs/SPEC.md` diz **o quê**: requisitos `RF-<MÓDULO>-NN`, regras `RN-NN`, modelo de domínio (§8), fases (§9) e questões em aberto `Q-NN` (§10).
- `docs/ARCHITECTURE.md` diz **como**: decisões D1–D17 (§2), módulos e dependências (§3), convenções de API (§7), segurança (§8), offline (§9), persistência (§11) e integrações (§12).
- `docs/roadmaps/` traz o plano de execução: `README.md` com o mapa dos 11 marcos (M0–M10) e um `roadmap-mN-*.md` por marco.

Documentação, mensagens ao usuário e commits ficam em pt-BR. Código, API e banco ficam em inglês (D17); o glossário da SPEC §4 faz o mapeamento dos termos.

## Conhecimento do projeto no Second Brain (obrigatório)

Este arquivo guarda só o mínimo. A referência técnica do projeto (estado por marco, arquitetura e estrutura de módulos, banco e migrações, tipos do kernel, convenções de API, segurança e autorização, auditoria, tempo real, offline, eventos e jobs, testes, Docker e CI, e cada módulo) vive no Second Brain: na nota de projeto do ZenyHotelManager em `10 Projects/` (o hub) e nas notas ligadas a ela.

- **Hook obrigatório:** `.claude/settings.json` roda o `load-brain.py` da skill `development-roadmap` (do Second Brain, em `~/.claude/skills/`) em `SessionStart` e `SubagentStart`. Ele injeta o hub e a lista das capturas pendentes do projeto, com a linha `BRAIN: carregado (ZenyHotelManager)`. Se essa linha não estiver no seu contexto, ou se aparecer `BRAIN: indisponível`, **pare antes de qualquer alteração** e peça ao usuário para configurar a máquina (`/configurar-maquina` numa sessão do vault) ou o vault (`SECOND_BRAIN_VAULT`). Não trabalhe de memória nem por suposição.
- **Antes de mexer num tema**, leia a nota do brain dele (link no hub) e as capturas pendentes do projeto que tratem dele: elas são mais novas que as notas e ainda não foram integradas. Vale também para subagentes.
- **Limites:** sessões deste repositório são agente externo do vault (skill `second-brain`): leem notas e só criam capturas novas em `00 Inbox/`; nunca editam notas, capturas existentes nem processam a Inbox.
- **Atualização:** ao fim de cada etapa de roadmap que mude algo descrito no brain (inclusive o estado), o orquestrador publica a captura `ZenyHotelManager - atualização <slug> Eyy` (o slug é o do arquivo do roadmap, como `m5-recepcao-fnrh`) com o texto novo ou alterado de cada nota afetada, dizendo qual nota ele substitui ou complementa. O curador consolida quando o usuário processar a Inbox. "Seção X do `CLAUDE.md`" num roadmap ou handoff antigo é a nota de tema correspondente.

## Stack e comandos

Stack: Kotlin 2.4 sobre JDK 25, Spring Boot 4.1, Spring Modulith 2.1, Spring Security 7, springdoc 3.1, Hibernate 7.4, Flyway 12, ShedLock 7, Testcontainers 2, Gradle 9.8 pelo wrapper. Use sempre `./gradlew`; não use o `gradle` do apt, que é antigo demais. Docker é obrigatório para `./gradlew build`/`test` (os testes de integração sobem um `postgres:18` e um MinIO pelo Testcontainers) e para o `docker/compose.yaml`.

**Comandos oficiais** (na raiz do repositório):

| Para quê | Comando | O que roda |
|---|---|---|
| Build completo | `./gradlew build` | compilação, testes (inclui `ModularityTest` e ArchUnit), detekt com e sem resolução de tipos, ktlint, `checkOpenApi` e `koverVerify` |
| Testes | `./gradlew test` | todos os testes; um só: `./gradlew test --tests 'com.zeny.hotel.ModularityTest'` |
| Lint | `./gradlew lint` | `detekt`, `detektMain`, `detektTest` e `ktlintCheck` |
| Corrigir formatação | `./gradlew ktlintFormat` | aplica o ktlint nos fontes |
| Cobertura | `./gradlew koverHtmlReport` | relatório em `build/reports/kover/html/` |
| Conferir o contrato | `./gradlew checkOpenApi` | gera o contrato da aplicação (`generateOpenApi`) e falha se `api/openapi.yaml` estiver diferente; já roda no `build` |
| Regenerar o contrato | `./gradlew updateOpenApi` | regrava `api/openapi.yaml`; versione-o junto com a mudança da API |
| Subir a aplicação | `./gradlew bootRun --args='--spring.profiles.active=local'` | fica rodando até Ctrl+C; precisa de um PostgreSQL, das chaves de cifragem e JWT e, no primeiro boot, do administrador inicial (nota de ambiente no brain) |
| Subir o ambiente completo | `docker compose -f docker/compose.yaml up -d --build` | `api`, `db`, `storage` (+ `storage-init`) e `proxy` em `http://localhost` (porta 80); precisa do `docker/.env`; derrube com `docker compose -f docker/compose.yaml down` (`down -v` apaga os volumes) |
| Só a imagem | `docker build -f docker/Dockerfile -t zeny-hotel-manager .` | build multi-estágio a partir da raiz (contexto filtrado pelo `.dockerignore`) |
| Breaking change na API | `.github/scripts/check-api-breaking.sh [ref]` | oasdiff entre o `api/openapi.yaml` do merge-base com `ref` (padrão `origin/main`) e o da árvore de trabalho; falha em mudança de nível `ERR` em `/api/v1`. Precisa de git e Docker |

Profiles: `local` (desenvolvimento) e `prod` (implantação), em `src/main/resources/application.yaml`. Não há profile padrão: informe sempre o profile. Antes de cada push, rode `./gradlew build` e `.github/scripts/check-api-breaking.sh` (push direto na `main` não é bloqueado pela CI).

## Como trabalhar com os roadmaps

- Os roadmaps ficam em `docs/roadmaps/`, um por marco (M5 a M10 no formato da skill `development-roadmap` com o plugin superpowers; M0 a M4, concluídos, no formato antigo). Para executar, cole o "Prompt de execução" do roadmap numa sessão limpa: ela segue o "Protocolo de execução" dele, planeja cada etapa com `superpowers:writing-plans`, executa com `superpowers:subagent-driven-development` na branch `roadmap/<slug>` e para no limite de etapas do lote. A tag de cada etapa (`#pendente`, `#em-andamento`, `#concluida` ou `#bloqueada`) e o "Evidências e handoff" ficam em dia na branch.
- Um marco só começa quando o gate do marco anterior estiver `#concluida`. As exceções estão em `docs/roadmaps/README.md`.
- Antes da primeira etapa de um marco, sincronize o roadmap com o código real usando a skill `development-roadmap` no modo atualização.
- Valide qualquer roadmap editado com `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py <arquivo>`.

## Decisões do usuário

Pontos que cabem ao usuário decidir (hipóteses, trade-offs, escolhas de versão ou ferramenta, "Revisão humana" de uma etapa) viram entrevista com `AskUserQuestion`, cada pergunta com a opção recomendada em primeiro lugar e o rótulo "(Recomendado)", até 4 por chamada, conforme a metodologia "Decidir pendências do usuário por entrevista com opção recomendada" do brain. Registre as decisões no handoff da etapa, nas hipóteses do roadmap ou na ARCHITECTURE, e as que mudam a referência do projeto também na captura de atualização. Subagentes não entrevistam: devolvem os pontos ao orquestrador.

## Git

Fora da execução de roadmap, trabalhe direto na branch `main`, sem criar branch de feature, salvo pedido explícito, e faça commit e push só quando o usuário pedir. A execução de roadmap é a exceção (decisão do usuário, 2026-10-22): roda na branch `roadmap/<slug>`, com os commits por tarefa do superpowers; merge, PR e push só com a decisão do usuário no fim do lote (`superpowers:finishing-a-development-branch`).
