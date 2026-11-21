-- O snapshot diário das estatísticas do hotel (M6 E23, D1; RF-REL): uma linha por data operacional, gravada uma vez
-- (o snapshot não é recalculado) a partir das views report_* dos módulos. Contagens disjuntas das noites vendidas
-- (sold E NÃO day use) por categoria de plano: rooms_sold (NORMAL), rooms_complimentary (COURTESY) e rooms_house_use
-- (HOUSE_USE); rooms_available = total menos fora de ordem do inventário; chegadas, saídas, no-shows e cancelamentos
-- do dia; receitas dos lançamentos do dia sem repasse (a taxa de serviço) e sem impostos. As derivadas ficam
-- gravadas: occupancy = (rooms_sold + rooms_complimentary) / rooms_available em pontos percentuais, adr = room_revenue
-- / rooms_sold e revpar = room_revenue / rooms_available, todos nulos com denominador zero (occupancy também acima
-- do que numeric(7,4) guarda). Só de inserção: o papel da aplicação não altera nem apaga.
CREATE TABLE reporting.daily_statistics
(
    id                     UUID                     NOT NULL,
    business_date          DATE                     NOT NULL,
    rooms_sold             BIGINT                   NOT NULL,
    rooms_complimentary    BIGINT                   NOT NULL,
    rooms_house_use        BIGINT                   NOT NULL,
    rooms_available        BIGINT                   NOT NULL,
    rooms_out_of_order     BIGINT                   NOT NULL,
    arrivals               BIGINT                   NOT NULL,
    departures             BIGINT                   NOT NULL,
    no_shows               BIGINT                   NOT NULL,
    cancellations          BIGINT                   NOT NULL,
    room_revenue           NUMERIC(14, 2)           NOT NULL,
    food_beverage_revenue  NUMERIC(14, 2)           NOT NULL,
    events_revenue         NUMERIC(14, 2)           NOT NULL,
    other_revenue          NUMERIC(14, 2)           NOT NULL,
    occupancy              NUMERIC(7, 4),
    adr                    NUMERIC(14, 2),
    revpar                 NUMERIC(14, 2),
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT daily_statistics_pk PRIMARY KEY (id),
    CONSTRAINT daily_statistics_date_uk UNIQUE (business_date),
    CONSTRAINT daily_statistics_counts_ck CHECK (
        rooms_sold >= 0 AND rooms_complimentary >= 0 AND rooms_house_use >= 0 AND rooms_available >= 0
            AND rooms_out_of_order >= 0 AND arrivals >= 0 AND departures >= 0 AND no_shows >= 0
            AND cancellations >= 0)
);

REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE reporting.daily_statistics FROM "${app_role}";
