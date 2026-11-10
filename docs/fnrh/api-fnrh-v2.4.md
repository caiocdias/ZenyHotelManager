# API FNRH v2.4 (Serpro/MTur): levantamento para o adaptador

Levantamento feito em 2026-11-04 (M5 E07, hipótese H6). A fonte é o "Documento de Integração, Versão 2.4 de 19/06/2026", baixado do link da SPEC §11 pela URL de arquivo do portal (`…/documentacao-api-v2-4-2.pdf/@@display-file/file`). São 60 páginas, com SHA-256 `476849fdb56642cb2d98abd24bb817a18dd583f1ff43081386f382e470cbf235`. O PDF não fica no repositório; este arquivo resume o que o adaptador e os stubs WireMock usam. Diante de uma divergência, vale o PDF. Diante de uma divergência entre o PDF e o comportamento real, vale a homologação (E09).

## Geral

- URLs base: produção `https://fnrh.turismo.serpro.gov.br/FNRH_API/rest/v2`; homologação `https://hom-lowcode.serpro.gov.br/FNRH_API/rest/v2`.
- HTTPS (TLS 1.2+), JSON UTF-8, versão na URL (`/v2`).
- Autenticação: HTTP Basic (RFC 7617), `Authorization: Basic base64(usuario:senha)`. Toda requisição leva `Content-Type: application/json`, salvo os corpos `text/plain` indicados abaixo.
- **`cpf_solicitante`** (header com um CPF válido, só dígitos): obrigatório **só** em `POST /pessoas`, `GET /pessoas/documento/{tipo}/{numero}` e `POST /hospedagem/registrar`. O MTur registra quem acessou dados pessoais.
- Datas: `YYYY-MM-DD`. Data e hora: ISO 8601 em UTC com `Z` (`2026-02-10T15:00:00.000Z`).
- Códigos de status documentados: `200` sucesso; `400` requisição inválida; `400 (VALIDATION)` parâmetros inválidos; `401` não autenticado; `403` não autorizado; `404` não encontrado; `500` erro interno. O único corpo de erro documentado é o do `401`: `{"erro": "Unauthorized", "mensagem": "Credenciais inválidas ou ausentes", "codigo": 401}`. O formato do `400` não é documentado; o adaptador lê `mensagem` se houver e trata o corpo como opaco.
- Listas paginadas (`GET /reservas`, `GET /fichas`): `page_number` começa em 1, e a resposta traz `pagination` (`TamanhoPagina`, `PaginaAtual`, `TotalRegistros`, `TotalPaginas`).

## Domínios (`GET`, resposta `{"dados": [{"id": "...", "label": "..."}]}`)

| Caminho | Valores documentados (`id` = `label`) |
|---|---|
| `/dominios/fnrh/meios_transporte` | `ONIBUS` = Ônibus; `AUTOMOVEL` = Automóvel; `AVIAO` = Avião; `MOTO` = Moto; `NAVIO_BARCO` = Navio/Barco; `PE` = A pé; `TREM` = Trem; `BICICLETA` = Bicicleta |
| `/dominios/fnrh/motivos_viagem` | `COMPRAS` = Compras; `CONGRESSO_FEIRA` = Congresso/Feira; `ESTUDOS_CURSOS` = Estudos/Cursos; `LAZER_FERIAS` = Lazer/Férias; `NEGOCIOS` = Negócios; `PARENTES_AMIGOS` = Parentes/Amigos; `RELIGIAO` = Religião; `SAUDE` = Saúde |
| `/dominios/hospedes/situacoes` | `CANCELADO` = Cancelado; `CHECKIN_REALIZADO` = Check-in realizado; `CHECKOUT_REALIZADO` = Check-out realizado; `NOSHOW` = No-Show; `PRECHECKIN_NAOVINCULADO` = Pré-check-in não vinculado; `PRECHECKIN_PENDENTE` = Pré-check-in pendente; `PRECHECKIN_REALIZADO` = Pré-check-in realizado |
| `/dominios/pessoas/generos` | `HOMEM` = Masculino; `MULHER` = Feminino; `OUTRO` = Outros; `NAOINFORMADO` = Prefiro não informar |
| `/dominios/pessoas/opcao_deficiencia` | `SIM` = Sim; `NAO` = Não; `NAOINFORMAR` = Prefiro não informar |
| `/dominios/pessoas/racas` | `AMARELA` = Amarelo; `BRANCA` = Branco; `INDIGENA` = Indígena; `PARDA` = Pardo; `PRETA` = Preto; `NAOINFORMAR` = Prefiro não informar |
| `/dominios/pessoas/tipos_deficiencia` | `FISICA` = Deficiência Física; `AUDITIVA_SURDEZ` = Deficiência Auditiva/Surdez; `VISUAL` = Deficiência Visual; `INTELECTUAL` = Deficiência Intelectual; `MULTIPLA` = Deficiência Múltipla |
| `/dominios/pessoas/tipos_documento` | `CPF` = CPF; `PASSAPORTE` = Passaporte |
| `/dominios/reservas/situacoes` | `CRIADA` = Criada; `EM_ANDAMENTO` = Em Andamento; `CONCLUIDA` = Concluída; `CANCELADA` = Cancelada; `NOSHOW` = No-Show |
| `/dominios/fichas/situacoes` | `AGUARDANDO_CHECKIN` = Aguardando Check-in; `AGUARDANDO_CHECKOUT` = Aguardando Check-out; `CHECK_OUT_CONFIRMADO` = Check-out confirmado; `INVALIDADA` = Invalidada; `NO_SHOW` = No show |

- Países (`PaisNacionalidade_id`, `PaisResidencia_id`): ISO 3166-1 alpha-2 ("tabela de Países IBGE"); a API não tem endpoint de países. Valores fora do ISO dão `400`.
- Municípios (`cidade_id`): código IBGE de 7 dígitos, cujos 2 primeiros são o código da UF; a API **não** tem endpoint de municípios. `estado_id` é a sigla da UF.

## Reservas

- `POST /reservas`: cria a reserva. Corpo: `numero_reserva` (único no meio de hospedagem), `numero_reserva_ota` (obrigatório se `origem_reserva_id` = `OTA`), `data_entrada`, `data_saida`, `quantidade_hospede_adulto`, `quantidade_hospede_menor`, `origem_reserva_id` (`MEIOHOSPEDAGEM` ou `OTA`). Resposta `{"reserva": {"reserva_id": "<uuid>", "situacao_reserva_id": "CRIADA", "link_precheckin": "…", …}}`.
- `GET /reservas` (filtros `page_number`, `situacao`, `codigo_reserva`, `data_entrada`, `data_saida`) e `GET /reservas/{id}`: consulta.
- `PUT /reservas/{id}`: substitui os dados (`numero_reserva`, `numero_reserva_ota`, `data_entrada`, `data_saida`, `quantidade_hospede_adulto`, `quantidade_hospede_menor`). Resposta: corpo vazio.
- `DELETE /reservas/{id}`: só em `CRIADA`. Resposta: corpo vazio.
- `POST /reservas/{reserva_id}/cancelar`: cancela a reserva e os hóspedes; **só em `CRIADA`**. Resposta: corpo vazio.
- `GET /reservas/{reserva_id}/hospedes`: hóspedes da reserva. Resposta `{"dados": [{"pessoa": {"pessoa_id", "nome", …, "numero", "tipo_documento_id"}, "hospede": {"hospede_id", "reserva_id", "pessoa_id", "situacao_hospede_id", "checkin_em", "checkout_em", …}}]}` (PDF, p. "Listar Hóspedes da Reserva"). Traz dado pessoal: o adaptador só lê `hospede.hospede_id` e `hospede.pessoa_id`.
- `POST /reservas/{reserva_id}/hospedes`: põe uma pessoa já registrada na reserva. Corpo: `pessoa_id`, `is_principal`, `situacao_hospede_id`, `checkin_em` e `checkout_em` (quando a situação os pede), `fnrh: {motivo_viagem_id, meio_transporte_id}` (quando `CHECKIN_REALIZADO`/`CHECKOUT_REALIZADO`), `responsavel_id` (id do **hóspede** responsável, para menor de 18 anos). Resposta `{"hospede_id": "<uuid>"}`.
- `POST /reservas/{reserva_id}/checkin`, `…/checkout`: em lote; corpo opcional **`text/plain`** com a data e hora UTC. `POST /reservas/{reserva_id}/noshow`: em lote, sem corpo. Resposta `{"dados": [{"hospede_id", "situacao_id", "data_hora"}]}`.
- `POST /reservas/{reserva_id}/vincular-hospede/{hospede_id}`: vincula um pré-check-in de balcão.

## Hóspedes

- `GET /hospedes/{id}`: hóspede e pessoa. `GET /hospedes/pre-checkins`: pré-check-ins de balcão.
- `PATCH /hospedes/{hospede_id}/checkin` e `PATCH /hospedes/{hospede_id}/checkout`: individuais, com corpo **`text/plain`** (data e hora UTC). Resposta `{"dados": {"hospede_id", "situacao_id", "data_hora"}}`.
- `PATCH /hospedes/{hospede_id}/no-show`: no-show individual, sem corpo. O exemplo do PDF usa `/noshow`, mas o título usa `/no-show`; a E09 confirma qual vale.
- **Não existe operação para desfazer check-in.**

## Pessoas (exigem `cpf_solicitante`, salvo `GET /pessoas/{id}`)

- `POST /pessoas`: corpo `{"pessoa": {nome, nome_social, PaisNacionalidade_id, genero_id, GeneroDescricao (obrigatório se OUTRO), data_nascimento, raca_id, deficiencia_id, tipo_deficiencia_id (obrigatório se deficiencia_id = SIM), documento_id: {numero_documento (só dígitos/letras), tipo_documento_id}, contato: {email, telefone, PaisResidencia_id, cep (8 dígitos), logradouro/numero/bairro (obrigatórios se cep), complemento, cidade_id e estado_id (obrigatórios se PaisResidencia_id = BR)}}}`. Resposta `{"pessoa_id": "<uuid>"}`.
- `GET /pessoas/{id}` e `GET /pessoas/documento/{tipo}/{numero}`: consulta. O número de documento vai no caminho, fora do nosso controle; nunca registrar a URL no log.
- `GET /pessoas/documento/{tipo}/{numero}` (exige `cpf_solicitante`; `tipo` = `CPF` ou `PASSAPORTE`): o PDF a indica para "validar se uma pessoa já existe no sistema antes de criar um registro". Resposta `{"dados": {"documento": {…}, "dado_pessoal": {"nome", "data_nascimento", …}, "contato": {…}}}`. Segundo a observação do PDF, **se a pessoa não está cadastrada no meio de hospedagem**, a resposta vem sem `id`, `dado_pessoal.genero`, `dado_pessoal.raca`, `dado_pessoal.deficiencia`, `contato.Email` e `contato.Telefone`. Logo: `dados.id` presente = pessoa já cadastrada neste meio de hospedagem (reaproveitar); ausente = criar com `POST /pessoas`. O exemplo do PDF não mostra o `id`; o nome do campo (`dados.id`) vem da observação, e a E09 confirma. Traz dado pessoal: o adaptador só lê `dados.id`.

## Hospedagem

- `POST /hospedagem/registrar` (exige `cpf_solicitante`): cria a reserva e registra todos os hóspedes numa chamada. Corpo `{"reserva": {campos do POST /reservas}, "dados_hospede": [{is_principal, situacao_hospede, check_in_em, check_out_em, dados_pessoais: {campos de pessoa}, responsavel: {numero_documento, tipo_documento_id} (para menor de 18 anos), dados_ficha: {motivo_viagem_id, meio_transporte_id}}]}`. Resposta `{"dados": {"reserva": {"reserva_id", "link_precheckin", …}, "dados_hospedes": [{"hospede_id", "hospede": {"pessoa_id", "reserva_id", "is_principal", "situacao_hospede_id"}}]}}`. A ordem de `dados_hospedes` não é garantida pelo PDF; o adaptador casa as pessoas pela posição e registra essa premissa (a E09 confirma).

## Fichas

- `GET /fichas` (filtros `page_number`, `status`, `tipo_documento`/`numero_documento`, `data_inicial`, `data_final`): fichas enviadas ao MTur. Fora do escopo do M5.

## Consequências para o projeto

- Documento aceito: só CPF ou passaporte. Um menor brasileiro só com RG **não** é aceito: precisa de CPF (nota B2 da E02).
- O cancelamento só é aceito com a reserva em `CRIADA`; depois do check-in, não há cancelamento.
- Não há desfazer check-in na API; o que fazer com `GuestCheckInUndone` fica para a E08.
- O no-show existe (individual e em lote); o envio dele fica para o M6 (H15).
- O link de pré-check-in volta em `POST /reservas` e em `POST /hospedagem/registrar` (RF-FNRH-04).
