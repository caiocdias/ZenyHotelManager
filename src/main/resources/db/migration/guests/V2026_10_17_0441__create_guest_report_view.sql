-- View de leitura para relatórios do módulo guests (M3 E03, ARCHITECTURE §11 "Leitura para relatórios"). Faz parte
-- da API do módulo: o reporting (M6) lê só ela, nunca as tabelas internas. Uma coluna publicada não muda de nome nem
-- de sentido; uma mudança incompatível cria outra view (report_guests_v2). Sem view de empresas (decisão do usuário
-- em 2026-10-17).
--
-- Uma linha por perfil de hóspede, em qualquer situação. **Sem dado pessoal** (RN-13, RN-19, RNF-09): nada de nome,
-- contato, data de nascimento, gênero, documento, endereço de rua, texto livre (observações, preferências, motivo
-- da restrição, restrições alimentares), empresa nem os dados sensíveis da FNRH (raça/cor e deficiência).
--
-- Colunas:
-- - guest_id: o id do perfil (opaco);
-- - status: ACTIVE, MERGED (absorvido por outro numa mesclagem de duplicados) ou ANONYMIZED (anonimizado a pedido
--   do titular);
-- - merged_into: o sobrevivente, num perfil MERGED (id opaco);
-- - anonymized: se o perfil foi anonimizado (inclusive o MERGED absorvido por um perfil anonimizado);
-- - nationality e residence_country: países pela ISO 3166-1 alfa-2 (nulos se não informados);
-- - state e ibge_city_code: a UF e o código IBGE do município do endereço no Brasil (o do endereço atual, ou o que a
--   anonimização guardou); nulos sem endereço;
-- - vip: a indicação VIP;
-- - created_at, merged_at e anonymized_at: quando o perfil foi criado, mesclado e anonimizado (UTC).
CREATE VIEW guests.report_guests AS
SELECT g.id                                          AS guest_id,
       g.status,
       g.merged_into,
       g.anonymized_at IS NOT NULL                   AS anonymized,
       g.nationality,
       g.residence_country,
       COALESCE(g.state, g.anonymized_state)         AS state,
       COALESCE(g.ibge_city_code, g.anonymized_ibge_city_code) AS ibge_city_code,
       g.vip,
       g.created_at,
       g.merged_at,
       g.anonymized_at
FROM guests.guest g;

-- O ALTER DEFAULT PRIVILEGES da pasta daria INSERT, UPDATE e DELETE: a view é só de leitura para a aplicação.
GRANT SELECT ON guests.report_guests TO "${app_role}";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON guests.report_guests FROM "${app_role}";
