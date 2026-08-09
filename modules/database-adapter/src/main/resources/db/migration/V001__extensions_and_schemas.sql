DO $$
BEGIN
    IF current_setting('server_version_num')::integer / 10000 <> 18 THEN
        RAISE EXCEPTION 'HDM005_POSTGRES_MAJOR_MISMATCH'
            USING ERRCODE = '55000';
    END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public;

DO $$
DECLARE
    installed_version text;
BEGIN
    SELECT extversion INTO installed_version
    FROM pg_catalog.pg_extension
    WHERE extname = 'vector';

    IF installed_version IS DISTINCT FROM '0.8.2' THEN
        RAISE EXCEPTION 'HDM005_PGVECTOR_VERSION_MISMATCH'
            USING ERRCODE = '55000';
    END IF;
END
$$;

CREATE SCHEMA evidence AUTHORIZATION hide_nest_migrator;
CREATE SCHEMA memory AUTHORIZATION hide_nest_migrator;
CREATE SCHEMA runtime AUTHORIZATION hide_nest_migrator;
CREATE SCHEMA security AUTHORIZATION hide_nest_migrator;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON SCHEMA evidence, memory, runtime, security FROM PUBLIC;

ALTER ROLE hide_nest_migrator
    SET search_path = pg_catalog, evidence, memory, runtime, security;
