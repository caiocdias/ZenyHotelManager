# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Projeto

Servidor do Zeny Hotel Manager: PMS e operações de **um único hotel** de até 30 UHs. Este repositório contém **só o servidor**. Os clientes (desktop, mobile, PDV, tela de produção e agente de impressão) ficam em outros repositórios e geram seus SDKs a partir do contrato OpenAPI.

- `docs/SPEC.md` diz **o quê**: requisitos `RF-<MÓDULO>-NN`, regras `RN-NN`, modelo de domínio (§8), fases (§9) e questões em aberto `Q-NN` (§10).
- `docs/ARCHITECTURE.md` diz **como**: decisões D1–D17 (§2), módulos e dependências (§3), convenções de API (§7), segurança (§8), offline (§9), persistência (§11) e integrações (§12).
- `docs/roadmaps/` traz o plano de execução: `README.md` com o mapa dos 11 marcos (M0–M10) e um `roadmap-mN-*.md` por marco.

Documentação, mensagens ao usuário e commits ficam em pt-BR. Código, API e banco ficam em inglês (D17); o glossário da SPEC §4 faz o mapeamento dos termos.

## Estado atual e comandos

Por enquanto o repositório só tem documentação. O esqueleto Gradle e os comandos oficiais de build, teste e lint são criados pela etapa **E01 do M0** (`docs/roadmaps/roadmap-m0-fundacao-tecnica.md`), que também deve atualizar esta seção. Não invente comandos antes disso.

Stack decidida: Kotlin 2.x sobre JDK 25, Spring Boot 4.x, Spring Modulith 2.x, PostgreSQL 18, Flyway e Testcontainers. O build roda pelo Gradle Wrapper (`./gradlew`); não use o `gradle` do apt, que é antigo demais. Docker é obrigatório para os testes de integração e para o `docker/compose.yaml`.

## Como trabalhar com os roadmaps

- Cada etapa tem um prompt pronto e segue o "Protocolo de execução" do próprio roadmap. Ao executar uma etapa, atualize a tag dela (`#pendente`, `#em-andamento`, `#concluida` ou `#bloqueada`) e preencha "Evidências e handoff".
- Um marco só começa quando o gate do marco anterior estiver `#concluida`. As exceções estão em `docs/roadmaps/README.md`.
- Os roadmaps de M1 em diante foram escritos antes de existir código. Antes da primeira etapa de um marco, sincronize o roadmap com o código real usando a skill `development-roadmap` no modo atualização.
- Valide qualquer roadmap editado com `python3 ~/.claude/skills/development-roadmap/scripts/check_roadmap.py <arquivo>`.

## Arquitetura em uma página

- **Monólito modular** com Spring Modulith. Cada pacote de primeiro nível sob o pacote base (`com.zeny.hotel`, a confirmar em M0 E01) é um módulo, correspondente a um contexto da SPEC.
  - O que fica na raiz do pacote do módulo é a API pública: interfaces, DTOs e eventos.
  - Os subpacotes `domain/`, `application/`, `infrastructure/` e `web/` são internos.
  - `domain` não depende de Spring; `web` só fala com `application`.
- **Dependências entre módulos seguem a regra por camadas** (ARCHITECTURE §3.1 e §3.2).
  - Um módulo pode depender de qualquer módulo de camada inferior e de qualquer módulo da Plataforma, desde que sem ciclo. Ao criar uma dependência nova, acrescente-a à tabela da §3.1 na mesma mudança.
  - Para reagir a algo de uma camada acima, inverta a dependência com um evento ou com uma porta definida no módulo de baixo.
  - `notifications` e `printing` nunca escutam eventos de módulos de negócio: quem gera o aviso, ou é dono do documento, chama esses módulos.
- **Fluxo de escrita** (ARCHITECTURE §5):
  1. O controller converte o DTO em comando.
  2. O caso de uso em `application` abre a transação e checa autorização (permissão, escopo `OWN`/`ALL`, limites, step-up).
  3. O agregado aplica as regras e registra eventos de domínio.
  4. No commit, os eventos vão para o *Event Publication Registry* (outbox) e a auditoria é gravada na mesma transação.
  5. Os listeners `@ApplicationModuleListener` rodam depois, de forma assíncrona e com reprocessamento.
- **Persistência:** um schema PostgreSQL por módulo, sem FK entre schemas. Migrações Flyway ficam numa pasta por módulo, com versão por timestamp (`V2026_10_09_1200__…`). Use JPA para escrita e `JdbcClient` para leitura. O módulo `reporting` lê só as views `report_*` publicadas pelos outros módulos.
- **Invariantes que o banco garante:**
  - Nunca há UH em dobro: `EXCLUDE USING gist` sobre as alocações.
  - A disponibilidade usa contadores por tipo e data com `SELECT … FOR UPDATE`.
  - Lançamentos e pagamentos são imutáveis (RN-03): o usuário da aplicação não tem `UPDATE` nem `DELETE` nessas tabelas, e a auditoria é *append-only*.
- **Tipos:**
  - IDs são UUIDv7 gerados na aplicação.
  - Dinheiro usa `Money` sobre `BigDecimal` / `numeric(14,2)`, nunca ponto flutuante.
  - Instantes ficam em `timestamptz` UTC; datas de negócio em `date`, seguindo a data operacional do hotel, que só avança no fechamento do dia.
- **API** (ARCHITECTURE §7):
  - Endpoints sob `/api/v1`, com erros RFC 9457 e `code` estável.
  - `Idempotency-Key` em operações financeiras e externas; `ETag`/`If-Match`; paginação por cursor.
  - O `api/openapi.yaml` é gerado no build e versionado. Nunca edite à mão: regenere e commite junto da mudança.
- **Offline e tempo real:**
  - O app operacional sincroniza por `/api/v1/sync/changes` (cursor) e `/api/v1/sync/commands`, com idempotência por `commandId`. Cada módulo registra seus `OfflineCommandHandler`.
  - Mudanças de estado chegam aos clientes por SSE em `/api/v1/stream`.
- **Integrações** (FNRH, channel manager, PSP Pix, adquirente, middleware fiscal, FCM): porta no módulo e adaptador por provedor, com outbox, retry e log redigido, testados com WireMock. Os fornecedores ainda não foram escolhidos (Q-05).

## Git

Trabalhe direto na branch `main`, sem criar branch de feature, salvo pedido explícito. Commit e push só quando o usuário pedir.
