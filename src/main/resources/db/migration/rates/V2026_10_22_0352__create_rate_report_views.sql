-- Views de leitura para relatórios do módulo rates (M4 E12; SPEC Q-15, ARCHITECTURE §11 "Leitura para relatórios").
-- Fazem parte da API do módulo: o reporting (M6) lê só elas, nunca as tabelas internas. Uma coluna publicada não muda
-- de nome nem de sentido; uma mudança incompatível cria outra view (report_x_v2). Cada linha é um fato (um plano, o
-- preço gravado de um tipo de UH numa data), sem agregar: quem consulta filtra e agrega.
--
-- Conteúdo: ids opacos (plano, plano base, políticas, tipo de UH), códigos e nomes de configuração do gestor (código e
-- nome do plano), situações, tipos, valores (numeric(14,2); percentual numeric(7,4)), datas de negócio (date) e
-- instantes (timestamptz, UTC). Fica FORA: a descrição do plano (texto livre) e qualquer dado de hóspede (o rates não
-- guarda nenhum).

-- Uma linha por plano tarifário (SPEC §8.2.5). meal_plan: ROOM_ONLY, BREAKFAST ou HALF_BOARD (pode crescer). category
-- (Q-15, fixa desde a criação do plano): NORMAL; COURTESY (cortesia: conta na ocupação e não no ADR); HOUSE_USE (uso
-- da casa: nem na ocupação nem no ADR). A mesma categoria vai copiada nas reservas (reservations.report_room_nights).
-- active: o plano está à venda. Derivação (H1; os preços de um derivado são calculados na leitura, nunca gravados, e
-- por isso não aparecem em report_rate_prices): base_rate_plan_id, o plano de que deriva; derivation_type PERCENT
-- (derivation_percent) ou AMOUNT (derivation_amount, por pessoa quando derivation_per_person); todas nulas no plano com
-- preços próprios. cancellation_policy_id e guarantee_policy_id: as políticas vigentes do plano.
CREATE VIEW rates.report_rate_plans AS
SELECT p.id AS rate_plan_id,
       p.code,
       p.name,
       p.meal_plan,
       p.category,
       p.active,
       p.base_rate_plan_id,
       p.derivation_type,
       p.derivation_percent,
       p.derivation_amount,
       p.derivation_per_person,
       p.cancellation_policy_id,
       p.guarantee_policy_id,
       p.created_at,
       p.updated_at
FROM rates.rate_plan p;

-- Uma linha por preço gravado de um plano com preços próprios, tipo de UH e data (RF-TAR-02): one_adult (a diária de um
-- adulto), two_adults (de dois; nula = a ocupação sem preço na data) e extra_adult (por adulto além do segundo; nula =
-- sem preço), por UH e noite. Os preços de criança por faixa etária e os dos planos derivados não estão aqui.
-- updated_at: a última gravação da linha.
CREATE VIEW rates.report_rate_prices AS
SELECT x.rate_plan_id,
       x.room_type_id,
       x.stay_date,
       x.one_adult,
       x.two_adults,
       x.extra_adult,
       x.updated_at
FROM rates.rate_price x;

-- O ALTER DEFAULT PRIVILEGES do schema daria INSERT, UPDATE e DELETE às views: o papel da aplicação só as lê.
GRANT SELECT ON rates.report_rate_plans TO "${app_role}";
GRANT SELECT ON rates.report_rate_prices TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON rates.report_rate_plans FROM "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON rates.report_rate_prices FROM "${app_role}";
