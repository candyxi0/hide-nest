-- V2 Formation runtime only. No source body or candidate write set is stored here.
ALTER TABLE runtime.formation_task DROP CONSTRAINT formation_task_state_check;
ALTER TABLE runtime.formation_task ADD CONSTRAINT formation_task_state_check CHECK (
    state IN ('PENDING','ATTEMPTING','RETRY_WAIT','ATTENTION_REQUIRED','COMMITTED_WRITE','COMMITTED_NO_CHANGE'));
ALTER TABLE runtime.formation_task DROP CONSTRAINT formation_task_generation_zero;
ALTER TABLE runtime.formation_task ADD CONSTRAINT formation_task_generation_nonnegative CHECK (generation >= 0);
ALTER TABLE runtime.formation_task ADD COLUMN predecessor_task_id uuid;
ALTER TABLE runtime.formation_task ADD COLUMN winner_attempt_id uuid;
ALTER TABLE runtime.formation_task ADD COLUMN settled_at timestamptz;
ALTER TABLE runtime.formation_task ADD CONSTRAINT formation_task_predecessor_fk
    FOREIGN KEY (predecessor_task_id) REFERENCES runtime.formation_task(task_id) ON DELETE NO ACTION;
ALTER TABLE runtime.formation_task ADD CONSTRAINT formation_task_not_own_predecessor
    CHECK (predecessor_task_id IS NULL OR predecessor_task_id <> task_id);
ALTER TABLE runtime.formation_task ADD CONSTRAINT formation_task_settlement_group CHECK (
    (state IN ('COMMITTED_WRITE','COMMITTED_NO_CHANGE') AND winner_attempt_id IS NOT NULL AND settled_at IS NOT NULL)
    OR (state NOT IN ('COMMITTED_WRITE','COMMITTED_NO_CHANGE') AND winner_attempt_id IS NULL AND settled_at IS NULL));
CREATE INDEX formation_task_predecessor_lookup ON runtime.formation_task(predecessor_task_id);
CREATE INDEX formation_task_lease_lookup ON runtime.formation_task(state, ready_at, task_id)
    WHERE state IN ('PENDING','ATTEMPTING','RETRY_WAIT');

CREATE TABLE runtime.formation_attempt (
    attempt_id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES runtime.formation_task(task_id) ON DELETE NO ACTION,
    generation bigint NOT NULL CHECK (generation > 0),
    owner_ref text COLLATE "C" NOT NULL CHECK (length(btrim(owner_ref)) BETWEEN 1 AND 128),
    lease_until timestamptz NOT NULL,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    state text COLLATE "C" NOT NULL CHECK (state IN ('RUNNING','STOP_REQUESTED','FAILED','WON')),
    result_kind text COLLATE "C" CHECK (result_kind IN ('WRITE_SET','NO_LONG_TERM_CHANGE','SOURCE_INCOMPLETE','FAILED','BUDGET_EXHAUSTED')),
    result_hash bytea CHECK (result_hash IS NULL OR octet_length(result_hash) = 32),
    idempotency_key uuid,
    failure_code text COLLATE "C" CHECK (failure_code IS NULL OR length(failure_code) BETWEEN 1 AND 64),
    CONSTRAINT formation_attempt_task_generation_unique UNIQUE(task_id, generation),
    CONSTRAINT formation_attempt_result_group CHECK (
        (result_kind IS NULL AND result_hash IS NULL AND idempotency_key IS NULL)
        OR (result_kind IS NOT NULL AND result_hash IS NOT NULL AND idempotency_key IS NOT NULL))
);
CREATE UNIQUE INDEX formation_attempt_one_winner ON runtime.formation_attempt(task_id) WHERE state='WON';
ALTER TABLE runtime.formation_task ADD CONSTRAINT formation_task_winner_fk
    FOREIGN KEY (winner_attempt_id) REFERENCES runtime.formation_attempt(attempt_id) ON DELETE NO ACTION;

CREATE TABLE runtime.formation_attention_notice (
    task_id uuid PRIMARY KEY REFERENCES runtime.formation_task(task_id) ON DELETE NO ACTION,
    created_at timestamptz NOT NULL
);

REVOKE ALL ON runtime.formation_task FROM PUBLIC, hide_nest_worker;
REVOKE ALL ON runtime.formation_attempt FROM PUBLIC, hide_nest_worker;
REVOKE ALL ON runtime.formation_attention_notice FROM PUBLIC, hide_nest_worker;
GRANT SELECT, INSERT, UPDATE ON runtime.formation_attempt TO hide_nest_api;
GRANT SELECT, INSERT ON runtime.formation_attention_notice TO hide_nest_api;
