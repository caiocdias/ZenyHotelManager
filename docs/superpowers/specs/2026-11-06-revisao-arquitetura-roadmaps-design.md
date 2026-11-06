# Revisão de arquitetura dos roadmaps M5 a M10

> Sessão `claude --agent arquiteto`, 2026-11-06, a partir de `ca9f9e2`. Pedido do usuário: "Refaça os roadmaps deste projeto com base no novo modelo de arquiteto e no descrito em /second-brain". Marcadores: **[usuário]** = decidido na entrevista desta sessão; **[arquiteto]** = decisão do arquiteto, revisável.

## Intenção

Os roadmaps M5 a M10 foram escritos em 2026-10-13, antes do código e antes da soul `arquiteto`. Esta revisão faz duas coisas:

1. alinha a execução ao modelo atual (template da skill `development-roadmap` e `orquestrador` de 2026-11-05);
2. relê as etapas pendentes pela lente do arquiteto (limites e contratos, irreversíveis primeiro, não funcionais, aderência a decisões), conferindo as hipóteses no código.

**Fora de escopo:** planos de etapa (são do orquestrador), código, teste e execução de qualquer etapa.

## Decisões

| # | Decisão | Alternativas descartadas | Quem |
|---|---|---|---|
| R1 | Manter os IDs e revisar o conteúdo: protocolo novo, hipóteses conferidas no código, dependências reordenadas e etapas novas com ID novo. | Só o protocolo (não aplica a lente); reescrever do zero com IDs novos (quebra as referências cruzadas e perde as armadilhas anotadas). | [usuário] |
| R2 | M0 a M4 ficam como registro histórico: concluídos, não se executam e continuam acusando erro de formato no verificador. | Migrar 1,5 MB de documentos sem efeito em execução futura. | [usuário] |
| R3 | No fim do lote, merge local na `main` com gate verde; o push continua com o usuário. `CLAUDE.md` e `docs/roadmaps/README.md` alinhados. | Reservar o merge ao usuário, o que exigiria instrução própria em cada prompt. | [usuário] |
| R4 | Expurgo dos dados sensíveis da FNRH (RN-19, Q-07) antes da inauguração: M8 E16, exigido no gate; a SPEC §9 passa a dizer isso. Anonimização de perfis e retenção da trilha seguem no M10 E13. | Manter tudo na Fase 4, com o hotel retendo dado sensível além do prazo por todo o intervalo M8–M10; expurgo manual, para o qual não há caminho hoje. | [usuário] |
| R5 | Descrição de gênero do hóspede em etapa própria antes do envio: M5 E15, dependência da E08. Resolve a decisão E07 (e). | Embutir na E08, já a mais carregada do marco; aceitar a limitação, o que força falsear o dado para cumprir a obrigação de registro. | [usuário] |
| R6 | Limpar credencial de integração antes de existir credencial real: M5 E16, pré-requisito da homologação E09; a regra vale para M7 e M8 desde a primeira linha. Resolve a decisão E07 (i). | Deixar para a prontidão do M8, com as credenciais reais de homologação entrando sem caminho de remoção. | [usuário] |
| R7 | M6 começa pelo fechamento: E10 e E11 antes da governança, com a E11 sem depender de E02 nem de E09. O snapshot e os PDFs saem para a E13, e o no-show na FNRH para a E14. | Manter a ordem original, com o único caminho que grava dinheiro imutável (RN-03) e faz a data andar no fim do marco; manter a E11 única, com três objetivos independentes. | [arquiteto] |
| R8 | Padrão de integração descrito pelo que o M5 E07 provou: resultado incerto, log sem texto livre, credencial fora da trilha, ambiente em vez de URL livre e status e métricas sem `HealthIndicator`. Falha alerta por chamada direta a `notifications`; o "mecanismo genérico de falha" que os roadmaps supunham não existe. ARCHITECTURE §12 e §16 corrigidas. | Criar o mecanismo genérico antes da segunda integração, sem evidência de que seja necessário. A extração para `shared` fica para a M7 E01 decidir, com a segunda integração à frente. | [arquiteto] |
| R9 | Migrações fora da `api` saem da M8 E11, bloqueada pela Q-06, para a M8 E14: a dívida não depende de nuvem ou hotel. | Deixar a dívida de segurança presa a uma decisão de hospedagem que não a afeta. | [arquiteto] |
| R10 | Pendências "antes da operação real" viram etapas com critério: re-cifragem (M8 E15) e cadeia de dependências (M8 E17), ambas exigidas no gate. | Mantê-las como notas: a re-cifragem constava como "decidir se entra", e o prazo do `opentelemetry-api` não tinha etapa dona. | [arquiteto] |
| R11 | O gate de inauguração (M8 E13) passa a exigir a homologação real da FNRH (M5 E09). Ela ficou fora do gate do M5 por exceção, e nenhum gate posterior a cobrava. | Só o checklist de inauguração, que não é critério de aceite. | [arquiteto] |
| R12 | M9 e M10 ganham a restrição de sistema em produção: migração só aditiva ou em duas fases, backup e procedimento de implantação antes de cada versão. | Tratar M9 e M10 como desenvolvimento sem dados, embora rodem depois da inauguração. | [arquiteto] |
| R13 | Decisões novas ficam nesta spec, na ARCHITECTURE e nos roadmaps. O projeto não tem `docs/adr/`; a convenção é a tabela de decisões da ARCHITECTURE §2 e os "Decisões" dos roadmaps. | Criar `docs/adr/` paralelo às duas fontes. | [arquiteto] |
| R14 | A comanda paga abre uma conta `WALK_IN` no `billing` (ARCHITECTURE D18, M8 E04). | Livro financeiro próprio no `pos`, que duplica taxa e conferência de caixa e cria duas fontes de receita. | [usuário] |
| R15 | Prometheus e Alertmanager no compose entregam os alertas (ARCHITECTURE D19, M8 E18). | Monitor externo gerenciado; decidir só com a Q-06, com alertas sem destinatário até lá. | [usuário] |
| R16 | O expurgo da M8 E16 fica retido por submissão `FAILED` ou `INVALID` só até um teto configurável; passado o teto, o dado sai e o caso fica nas pendências da FNRH. | Reter sem limite, o que descumpre a RN-19 com um `INVALID` esquecido; não reter, o que pode perder dado de um reenvio ainda possível. | [arquiteto] |

## Hipóteses conferidas no código (`ca9f9e2`)

| Hipótese | Resultado |
|---|---|
| M6 H2: base offline e execução de checklist | Confirmada, com outra assinatura: `ChecklistsApi.startExecution` e `completeExecution`. |
| M6 H3: comandos de status em `rooms` | Parcial: `markClean`, `markInspected`, `rejectInspection`, `markDirty`, `setServiceFlag` e OOO existem na `RoomsApi`; a discrepância (RF-UH-08) não está na API pública. |
| M6 H4: consultas por data e `markNoShow` | Falsa: a `ReservationsApi` não tem no-show nem listas por data (só endpoints); `releaseUnused` e `blocksDueForRelease` existem. |
| M6 H5: idempotência em `billing` | Confirmada por `NewPosting.sourceReference` (M5 E04), com chave definida para o fechamento usar. Não há conceito de data fechada: `post` grava na data corrente. |
| M6 H6: aresta `endofday` → `files` "já listada" | Falsa: a tabela §3.1 não a lista. Os PDFs ficam com `reporting`, que tem a aresta. |
| M6: `fnrh.report_pending_submissions` "já existe" | Falsa: o nome previsto na M5 E08 é `fnrh.report_submissions`, ainda a criar. |
| M7 H2: "evento genérico de falha" e indicador de saúde no actuator | Falsa: o M5 usa `NotificationsApi.notify` direto, `GET /fnrh/status` e `MeterBinder`, sem `HealthIndicator`. |
| M8: serviço `backup` "reservado no compose" | Parcial: só um comentário em `docker/compose.yaml`; o serviço é a criar. |
| M8: `opentelemetry-api` | Confirmado na 1.62.0 no `gradle.lockfile`, com Spring Boot 4.1.2; segue o risco aceito com prazo (M5 E14 a). |
| M10 H6: dono dos dados sensíveis da FNRH | Resolvida: `guests` guarda (`sensitive_data`); `fnrh` não guarda, por critério da M5 E08. |

## Etapas novas

| Etapa | Objetivo | Depende de |
|---|---|---|
| M5 E15 | Descrição de gênero no perfil e nos dados da FNRH | E07 |
| M5 E16 | Limpar a credencial do Serpro | E07 |
| M6 E13 | Snapshot e arquivo do fechamento | E09, E11 |
| M6 E14 | No-show na FNRH | E11 |
| M8 E14 | Migrações fora da `api` | — |
| M8 E15 | Re-cifragem de todos os valores cifrados | — |
| M8 E16 | Expurgo dos dados sensíveis da FNRH | — |
| M8 E17 | Cadeia de dependências pronta para produção | — |
| M8 E18 | Coleta e entrega de alertas | E10 |

Nenhuma etapa foi cancelada ou renumerada.

## Revisão independente

O `revisor-arquitetura` leu os roadmaps revistos e devolveu **bloqueado**: 0 Críticos, 10 Importantes e 9 Menores. Conferi no código e no compose os achados mais fortes (`BusinessDay` em `hotel`, `folioId` obrigatório no `Payment`, `422 CASHIER_SHIFT_REQUIRED`, `dietary_restrictions`, compose sem coletor) e tratei todos: dois viraram decisão do usuário (R14 e R15), e os demais, correção de dependência ou de critério. O revisor não releu o resultado das correções, e as etapas M7 E03, E04, E05, E07 e E09 a E12 ficaram fora do pedido feito a ele.

## Limites desta revisão

- Fornecedores e hospedagem (Q-05, Q-06, Q-02, Q-03, Q-13) seguem como decisões do usuário e as etapas afetadas, `#bloqueada`.
- As hipóteses que dependem de M5 a M8 ainda não feitos seguem como hipóteses: a sincronização antes de cada marco (regra 2 de `docs/roadmaps/README.md`) continua valendo.
- O M8 ficou com 18 etapas, acima da referência de ~12 da skill. Ficam agrupadas em blocos no índice; a divisão em dois arquivos é oferecida ao usuário, não aplicada.
