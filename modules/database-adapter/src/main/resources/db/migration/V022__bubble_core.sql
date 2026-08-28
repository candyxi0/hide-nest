-- V022 | Bubble V1 core
--
-- Platform-neutral, body-free facts for one-memory Bubble resolution. Query text and memory
-- body text are deliberately absent. A turn receipt doubles as the no-body resolution audit;
-- the optional item and room ledger are closed against it at COMMIT.

CREATE FUNCTION runtime.valid_bubble_key(value text)
RETURNS boolean
LANGUAGE plpgsql
IMMUTABLE
STRICT
AS $$
DECLARE
    i integer;
    cp integer;
BEGIN
    IF octet_length(value) < 1 OR octet_length(value) > 128 OR btrim(value) = '' THEN
        RETURN false;
    END IF;
    FOR i IN 1..char_length(value) LOOP
        cp := ascii(substr(value, i, 1));
        IF cp BETWEEN 0 AND 31 OR cp BETWEEN 127 AND 159 THEN
            RETURN false;
        END IF;
    END LOOP;
    RETURN true;
END
$$;

CREATE TABLE runtime.bubble_turn_receipt (
    space_key text COLLATE "C" NOT NULL,
    room_key text COLLATE "C" NOT NULL,
    turn_key text COLLATE "C" NOT NULL,
    request_hash bytea NOT NULL,
    query_utf8_bytes integer NOT NULL,
    result_category text COLLATE "C" NOT NULL,
    policy_version text COLLATE "C" NOT NULL,
    min_score double precision NOT NULL,
    result_manifest_hash bytea NOT NULL,
    issued_at timestamptz NOT NULL,
    CONSTRAINT bubble_turn_receipt_pk PRIMARY KEY (space_key, room_key, turn_key),
    -- turnKey is the V1 idempotency fact key. This additionally makes a changed room binding
    -- an explicit conflict instead of creating a second receipt.
    CONSTRAINT bubble_turn_receipt_turn_unique UNIQUE (turn_key),
    CONSTRAINT bubble_turn_receipt_space_key_check CHECK (runtime.valid_bubble_key(space_key)),
    CONSTRAINT bubble_turn_receipt_room_key_check CHECK (runtime.valid_bubble_key(room_key)),
    CONSTRAINT bubble_turn_receipt_turn_key_check CHECK (runtime.valid_bubble_key(turn_key)),
    CONSTRAINT bubble_turn_receipt_request_hash_check CHECK (octet_length(request_hash) = 32),
    CONSTRAINT bubble_turn_receipt_query_bytes_check CHECK (query_utf8_bytes BETWEEN 1 AND 480),
    CONSTRAINT bubble_turn_receipt_result_check CHECK (result_category IN ('BUBBLE_READY', 'NO_MATCH')),
    CONSTRAINT bubble_turn_receipt_policy_check CHECK (
        octet_length(policy_version) BETWEEN 1 AND 64 AND policy_version = btrim(policy_version)),
    CONSTRAINT bubble_turn_receipt_min_score_check CHECK (
        min_score >= 0.40 AND min_score <= 0.95 AND min_score <> 'NaN'::double precision),
    CONSTRAINT bubble_turn_receipt_manifest_hash_check CHECK (octet_length(result_manifest_hash) = 32)
);

CREATE TABLE runtime.bubble_delivery_item (
    space_key text COLLATE "C" NOT NULL,
    room_key text COLLATE "C" NOT NULL,
    turn_key text COLLATE "C" NOT NULL,
    memory_id uuid NOT NULL,
    memory_revision_id uuid NOT NULL,
    revision_no bigint NOT NULL,
    policy_revision_no bigint NOT NULL,
    score double precision NOT NULL,
    memory_type text COLLATE "C" NOT NULL,
    evidence_age_days integer NOT NULL,
    CONSTRAINT bubble_delivery_item_pk PRIMARY KEY (space_key, room_key, turn_key),
    CONSTRAINT bubble_delivery_item_receipt_fk
        FOREIGN KEY (space_key, room_key, turn_key)
        REFERENCES runtime.bubble_turn_receipt (space_key, room_key, turn_key)
        ON DELETE NO ACTION,
    CONSTRAINT bubble_delivery_item_revision_no_check CHECK (revision_no >= 1),
    CONSTRAINT bubble_delivery_item_policy_revision_check CHECK (policy_revision_no >= 1),
    CONSTRAINT bubble_delivery_item_score_check CHECK (
        score >= -1.0 AND score <= 1.0 AND score <> 'NaN'::double precision),
    CONSTRAINT bubble_delivery_item_memory_type_check CHECK (
        memory_type IN ('EVENT', 'CLAIM', 'QUOTE', 'INTERPRETATION', 'CALIBRATION', 'PRINCIPLE')),
    CONSTRAINT bubble_delivery_item_evidence_age_check CHECK (evidence_age_days >= 0)
);

CREATE TABLE runtime.bubble_room_revision_ledger (
    space_key text COLLATE "C" NOT NULL,
    room_key text COLLATE "C" NOT NULL,
    memory_revision_id uuid NOT NULL,
    turn_key text COLLATE "C" NOT NULL,
    delivered_at timestamptz NOT NULL,
    CONSTRAINT bubble_room_revision_ledger_pk PRIMARY KEY (space_key, room_key, memory_revision_id),
    CONSTRAINT bubble_room_revision_ledger_turn_unique UNIQUE (space_key, room_key, turn_key),
    CONSTRAINT bubble_room_revision_ledger_receipt_fk
        FOREIGN KEY (space_key, room_key, turn_key)
        REFERENCES runtime.bubble_turn_receipt (space_key, room_key, turn_key)
        ON DELETE NO ACTION
);

-- Bubble facts are immutable by default. DELETE is opened only inside the narrow room-purge
-- function through a transaction-local marker; direct UPDATE is never allowed.
CREATE FUNCTION runtime.reject_bubble_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND current_setting('hidenest.bubble_room_purge', true) = 'on' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'HDM022_BUBBLE_FACT_IMMUTABLE' USING ERRCODE = '23514';
END
$$;

CREATE TRIGGER bubble_turn_receipt_immutable
    BEFORE UPDATE OR DELETE ON runtime.bubble_turn_receipt
    FOR EACH ROW EXECUTE FUNCTION runtime.reject_bubble_mutation();
CREATE TRIGGER bubble_delivery_item_immutable
    BEFORE UPDATE OR DELETE ON runtime.bubble_delivery_item
    FOR EACH ROW EXECUTE FUNCTION runtime.reject_bubble_mutation();
CREATE TRIGGER bubble_room_revision_ledger_immutable
    BEFORE UPDATE OR DELETE ON runtime.bubble_room_revision_ledger
    FOR EACH ROW EXECUTE FUNCTION runtime.reject_bubble_mutation();

-- Deferred closure makes receipt + optional item + optional ledger one indivisible fact set.
-- One trigger function re-validates the parent receipt on every deferred firing, so child rows
-- added in a LATER transaction after the receipt committed cannot poison a closed fact set.
-- Trigger-returning functions stay invisible to jOOQ codegen; keep this the only closure routine.
CREATE FUNCTION runtime.enforce_bubble_receipt_closure()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    receipt runtime.bubble_turn_receipt%ROWTYPE;
    item_count bigint;
    ledger_count bigint;
BEGIN
    SELECT * INTO receipt
      FROM runtime.bubble_turn_receipt r
     WHERE r.space_key = NEW.space_key
       AND r.room_key = NEW.room_key
       AND r.turn_key = NEW.turn_key;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'HDM022_BUBBLE_RESULT_ITEM_CLOSURE_INVALID' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) INTO item_count
      FROM runtime.bubble_delivery_item i
     WHERE i.space_key = NEW.space_key
       AND i.room_key = NEW.room_key
       AND i.turn_key = NEW.turn_key;
    SELECT count(*) INTO ledger_count
      FROM runtime.bubble_room_revision_ledger l
     WHERE l.space_key = NEW.space_key
       AND l.room_key = NEW.room_key
       AND l.turn_key = NEW.turn_key;

    IF (receipt.result_category = 'NO_MATCH' AND (item_count <> 0 OR ledger_count <> 0))
       OR (receipt.result_category = 'BUBBLE_READY' AND (item_count <> 1 OR ledger_count <> 1)) THEN
        RAISE EXCEPTION 'HDM022_BUBBLE_RESULT_ITEM_CLOSURE_INVALID' USING ERRCODE = '23514';
    END IF;
    IF item_count = 1 AND EXISTS (
        SELECT 1
          FROM runtime.bubble_delivery_item i
          JOIN runtime.bubble_room_revision_ledger l
            ON l.space_key = i.space_key
           AND l.room_key = i.room_key
           AND l.turn_key = i.turn_key
         WHERE i.space_key = NEW.space_key
           AND i.room_key = NEW.room_key
           AND i.turn_key = NEW.turn_key
           AND (l.memory_revision_id IS DISTINCT FROM i.memory_revision_id
             OR l.delivered_at IS DISTINCT FROM receipt.issued_at
             OR i.score < receipt.min_score)
    ) THEN
        RAISE EXCEPTION 'HDM022_BUBBLE_BINDING_CLOSURE_INVALID' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END
$$;

CREATE CONSTRAINT TRIGGER bubble_turn_receipt_closure_guard
    AFTER INSERT ON runtime.bubble_turn_receipt
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_bubble_receipt_closure();
CREATE CONSTRAINT TRIGGER bubble_delivery_item_closure_guard
    AFTER INSERT ON runtime.bubble_delivery_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_bubble_receipt_closure();
CREATE CONSTRAINT TRIGGER bubble_room_revision_ledger_closure_guard
    AFTER INSERT ON runtime.bubble_room_revision_ledger
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION runtime.enforce_bubble_receipt_closure();

CREATE FUNCTION runtime.purge_bubble_room(p_space_key text, p_room_key text)
RETURNS TABLE(receipts_deleted bigint, items_deleted bigint, ledger_deleted bigint)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, runtime
AS $$
BEGIN
    IF NOT runtime.valid_bubble_key(p_space_key) OR NOT runtime.valid_bubble_key(p_room_key) THEN
        RAISE EXCEPTION 'HDM022_BUBBLE_PURGE_KEY_INVALID' USING ERRCODE = '22023';
    END IF;
    PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(p_space_key || E'\x1f' || p_room_key, 220022));
    SELECT count(*) INTO receipts_deleted
      FROM runtime.bubble_turn_receipt
     WHERE space_key = p_space_key AND room_key = p_room_key;
    SELECT count(*) INTO items_deleted
      FROM runtime.bubble_delivery_item
     WHERE space_key = p_space_key AND room_key = p_room_key;
    SELECT count(*) INTO ledger_deleted
      FROM runtime.bubble_room_revision_ledger
     WHERE space_key = p_space_key AND room_key = p_room_key;
    PERFORM pg_catalog.set_config('hidenest.bubble_room_purge', 'on', true);
    DELETE FROM runtime.bubble_room_revision_ledger
     WHERE space_key = p_space_key AND room_key = p_room_key;
    DELETE FROM runtime.bubble_delivery_item
     WHERE space_key = p_space_key AND room_key = p_room_key;
    DELETE FROM runtime.bubble_turn_receipt
     WHERE space_key = p_space_key AND room_key = p_room_key;
    RETURN NEXT;
END
$$;

REVOKE ALL ON runtime.bubble_turn_receipt FROM PUBLIC, hide_nest_api, hide_nest_worker;
REVOKE ALL ON runtime.bubble_delivery_item FROM PUBLIC, hide_nest_api, hide_nest_worker;
REVOKE ALL ON runtime.bubble_room_revision_ledger FROM PUBLIC, hide_nest_api, hide_nest_worker;
GRANT SELECT, INSERT ON runtime.bubble_turn_receipt TO hide_nest_api;
GRANT SELECT, INSERT ON runtime.bubble_delivery_item TO hide_nest_api;
GRANT SELECT, INSERT ON runtime.bubble_room_revision_ledger TO hide_nest_api;

REVOKE ALL ON FUNCTION runtime.valid_bubble_key(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION runtime.reject_bubble_mutation() FROM PUBLIC;
REVOKE ALL ON FUNCTION runtime.enforce_bubble_receipt_closure() FROM PUBLIC;
REVOKE ALL ON FUNCTION runtime.purge_bubble_room(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION runtime.valid_bubble_key(text) TO hide_nest_api;
GRANT EXECUTE ON FUNCTION runtime.purge_bubble_room(text, text) TO hide_nest_api;
