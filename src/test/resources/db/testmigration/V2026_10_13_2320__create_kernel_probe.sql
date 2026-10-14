-- Apoio só de teste (não vai para produção): colunas com os tipos do kernel `shared` (M0 E03).
CREATE TABLE test_support.kernel_probe
(
    id             UUID          NOT NULL PRIMARY KEY,
    doc_number     VARCHAR(20)   NOT NULL,
    amount         NUMERIC(14, 2) NOT NULL,
    discount       NUMERIC(7, 4) NOT NULL,
    cpf            VARCHAR(11)   NOT NULL,
    cnpj           VARCHAR(14)   NOT NULL,
    document       TEXT          NOT NULL,
    street         VARCHAR(200)  NOT NULL,
    number         VARCHAR(20)   NOT NULL,
    complement     VARCHAR(100),
    district       VARCHAR(100)  NOT NULL,
    city           VARCHAR(100)  NOT NULL,
    state          VARCHAR(2)    NOT NULL,
    postal_code    VARCHAR(8)    NOT NULL,
    ibge_city_code VARCHAR(7)    NOT NULL
);

-- Ids relacionados numa coleção: o único caso em que um id tipado chega boxed ao Hibernate.
CREATE TABLE test_support.kernel_probe_related
(
    probe_id   UUID NOT NULL REFERENCES test_support.kernel_probe (id),
    related_id UUID NOT NULL,
    PRIMARY KEY (probe_id, related_id)
);

-- Sequência de um tipo de documento, como cada módulo criará a sua.
CREATE SEQUENCE test_support.probe_number_seq;
