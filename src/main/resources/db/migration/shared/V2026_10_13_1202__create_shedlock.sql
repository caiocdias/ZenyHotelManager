-- Trava dos jobs agendados (ShedLock JDBC, ARCHITECTURE §13).
-- Exceção consciente à regra de `timestamptz`: o ShedLock usa a hora do banco
-- (`usingDbTime()`), que grava `timezone('utc', CURRENT_TIMESTAMP)`, um timestamp sem fuso já
-- em UTC. Com coluna sem fuso a comparação não depende do fuso da sessão (esquema oficial do
-- ShedLock para PostgreSQL).
CREATE TABLE shared.shedlock
(
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
