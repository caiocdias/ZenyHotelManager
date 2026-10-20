-- Views de leitura para relatórios do módulo billing (M3 E10, RF-CTA-11, ARCHITECTURE §11 "Leitura para
-- relatórios"; nomes e colunas decididos pelo usuário em 2026-10-20). Fazem parte da API do módulo: o reporting (M6)
-- lê só elas, nunca as tabelas internas. Uma coluna publicada não muda de nome nem de sentido; uma mudança
-- incompatível cria outra view (report_x_v2). Cada linha é um fato (uma conta, um lançamento, um pagamento, um turno,
-- uma forma contada), sem agregar por período: quem consulta filtra e agrega.
--
-- Conteúdo: ids opacos (conta, dono, empresa pagadora, código, regra, turno, ponto de caixa, usuário, aprovação,
-- intenção de pagamento, caução), situações, tipos, valores (numeric(14,2), percentual numeric(7,4)), datas
-- operacionais (date) e instantes (timestamptz, UTC). Nomes de configuração do gestor podem ir (código e nome do
-- código de lançamento, nome do ponto de caixa: nunca nome de pessoa). Fica FORA: todo texto livre digitado por quem
-- opera (a descrição do lançamento, o motivo de correção, de reembolso, de reabertura e de sangria/suprimento), dado
-- de hóspede (o billing não guarda nenhum: o dono da conta é um id opaco) e, do cartão, tudo além da bandeira e dos 4
-- últimos dígitos (nada de NSU nem de código de autorização, RNF-10).

-- Uma linha por conta (folio). owner_type: RESERVATION, GROUP, EVENT, COMPANY ou WALK_IN (a avulsa sem dono tem a
-- própria conta como dona); owner_id é opaco (só COMPANY é uma empresa do guests). status: OPEN ou CLOSED.
-- credit_limit nulo = sem limite. opened_on/closed_on/reopened_on são datas operacionais; closed_on/closed_at só na
-- conta encerrada (o encerramento corrente); reopened_on/reopened_at são da última reabertura. O saldo não está aqui:
-- é a soma de report_postings.amount menos a soma de report_payments.net_amount da conta.
CREATE VIEW billing.report_folios AS
SELECT f.id     AS folio_id,
       f.number AS folio_number,
       f.owner_type,
       f.owner_id,
       f.payer_company_id,
       f.status,
       f.credit_limit,
       f.opened_on,
       f.opened_at,
       f.closed_on,
       f.closed_at,
       f.reopened_on,
       f.reopened_at
FROM billing.folio f;

-- Uma linha por lançamento (imutável, RN-03). posting_type: REGULAR (normal), REVERSAL (estorno), ADJUSTMENT
-- (ajuste), TRANSFER_OUT/TRANSFER_IN (saída e entrada de transferência ou divisão) ou SERVICE_CHARGE (taxa de
-- serviço lançada junto de um item); a lista pode crescer. amount tem sinal (débito positivo; crédito e correção de
-- débito negativos) e a soma por conta é o total lançado nela. transaction_code, transaction_code_name: o código curto
-- e o nome atuais (configuração); transaction_code_kind (CHARGE, PENALTY, SERVICE_CHARGE, TAX, CREDIT) e
-- revenue_group (ROOM, FOOD_BEVERAGE, SERVICE_CHARGE, FEES, TAXES, LAUNDRY, OTHER) são fixos desde a criação do
-- código. pass_through: o valor é repasse, não receita do hotel (hoje só o grupo SERVICE_CHARGE, a taxa de serviço,
-- RN-10; a mesma regra de RevenueGroup.passThrough). business_date é a data operacional do lançamento (RN-01);
-- posted_by nulo = o sistema. source: MANUAL, POS, END_OF_DAY, LAUNDRY ou EVENT. Vínculos: corrects_id (o lançamento
-- que o estorno, o ajuste ou a saída de transferência corrige), transferred_from_id (na entrada, a saída),
-- routed_from_folio_id e routing_rule_id (lançamento roteado: a conta pedida e a regra), service_charge_for_id e
-- service_charge_rate (a taxa de serviço: o item e o percentual aplicado), triggered_by_id (correção ou saída da taxa
-- feita junto da do item; um estorno de taxa sem ele é a remoção a pedido do cliente). approval_id e approved_by: a
-- aprovação de superior da correção, se houve.
CREATE VIEW billing.report_postings AS
SELECT p.id                                AS posting_id,
       p.folio_id,
       p.posting_type,
       p.transaction_code_id,
       c.code                              AS transaction_code,
       c.name                              AS transaction_code_name,
       c.kind                              AS transaction_code_kind,
       c.revenue_group,
       (c.revenue_group = 'SERVICE_CHARGE') AS pass_through,
       p.quantity,
       p.unit_price,
       p.amount,
       p.business_date,
       p.posted_at,
       p.posted_by,
       p.source,
       p.corrects_id,
       p.transferred_from_id,
       p.routed_from_folio_id,
       p.routing_rule_id,
       p.service_charge_for_id,
       p.service_charge_rate,
       p.triggered_by_id,
       p.approval_id,
       p.approved_by
FROM billing.posting p
         JOIN billing.transaction_code c ON c.id = p.transaction_code_id;

-- Uma linha por recebimento ou reembolso (imutável, RN-03). payment_type: PAYMENT (recebimento) ou REFUND
-- (reembolso de um recebimento da mesma conta, refund_of_id); a lista pode crescer. method: CASH, DEBIT_CARD,
-- CREDIT_CARD, PIX, BANK_TRANSFER ou OTHER (pode crescer). amount é sempre positivo; net_amount tem o efeito no
-- recebido (positivo no recebimento, negativo no reembolso): o saldo da conta é a soma dos lançamentos menos a soma de
-- net_amount. tendered e change_given: o entregue e o troco (só no dinheiro). deposit: depósito (sinal antes da
-- chegada, RF-CTA-07; o reembolso de um depósito também) — o razão de depósitos é esta view filtrada por deposit.
-- cashier_shift_id: o turno de quem recebeu ou devolveu; received_by: o usuário. payment_intent_id: a intenção de
-- pagamento do payments (só no recebimento). card_pre_authorization_id: a caução capturada (a captura é um
-- recebimento). card_brand e card_last4: só no cartão. approval_id e approved_by: a aprovação do reembolso, se houve.
CREATE VIEW billing.report_payments AS
SELECT y.id AS payment_id,
       y.folio_id,
       y.payment_type,
       y.method,
       y.amount,
       CASE WHEN y.payment_type = 'PAYMENT' THEN y.amount ELSE -y.amount END AS net_amount,
       y.tendered,
       y.change_given,
       y.deposit,
       y.cashier_shift_id,
       y.business_date,
       y.received_at,
       y.received_by,
       y.payment_intent_id,
       y.refund_of_id,
       y.card_pre_authorization_id,
       y.card_brand,
       y.card_last4,
       y.approval_id,
       y.approved_by
FROM billing.payment y;

-- Uma linha por turno de caixa. status: OPEN ou CLOSED. cash_point_name é o nome atual do ponto (configuração, nunca
-- nome de pessoa); operator_id é o usuário que opera o turno. blind_close: fechamento cego (copiado do ponto na
-- abertura). opening_float: o fundo de troco (dinheiro). opened_on/closed_on são datas operacionais; closed_on,
-- closed_at e difference_total (a soma das diferenças por forma: positivo sobra, negativo falta) só no turno fechado.
-- O esperado e a contagem de cada forma ficam em report_cashier_counts.
CREATE VIEW billing.report_cashier_shifts AS
SELECT s.id      AS shift_id,
       s.number  AS shift_number,
       s.cash_point_id,
       cp.name   AS cash_point_name,
       s.operator_id,
       s.status,
       s.blind_close,
       s.opening_float,
       s.opened_on,
       s.opened_at,
       s.closed_on,
       s.closed_at,
       s.difference_total
FROM billing.cashier_shift s
         JOIN billing.cash_point cp ON cp.id = s.cash_point_id;

-- Uma linha por forma de pagamento contada no fechamento de um turno (todas as formas, inclusive as zeradas): o
-- declarado pelo operador, o esperado calculado (fundo, sangrias e suprimentos no dinheiro, recebimentos menos
-- reembolsos) e a diferença (declarado - esperado). Só turnos fechados têm contagem; o ponto, o operador e a data e o
-- instante do fechamento vêm do turno, para filtrar sem juntar.
CREATE VIEW billing.report_cashier_counts AS
SELECT k.shift_id,
       s.cash_point_id,
       s.operator_id,
       s.closed_on,
       s.closed_at,
       k.method,
       k.declared,
       k.expected,
       k.difference
FROM billing.cashier_shift_count k
         JOIN billing.cashier_shift s ON s.id = k.shift_id;

-- Privilégios (convenção do CLAUDE.md, "Views de relatório"): o ALTER DEFAULT PRIVILEGES da pasta também vale para
-- views e daria DML ao papel da aplicação; ele só lê. A view roda com os privilégios do dono (sem security_invoker),
-- então quem só tem SELECT nela não precisa de nada nas tabelas.
GRANT SELECT ON billing.report_folios TO "${app_role}";
GRANT SELECT ON billing.report_postings TO "${app_role}";
GRANT SELECT ON billing.report_payments TO "${app_role}";
GRANT SELECT ON billing.report_cashier_shifts TO "${app_role}";
GRANT SELECT ON billing.report_cashier_counts TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON billing.report_folios FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON billing.report_postings FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON billing.report_payments FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON billing.report_cashier_shifts FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON billing.report_cashier_counts FROM "${app_role}";
