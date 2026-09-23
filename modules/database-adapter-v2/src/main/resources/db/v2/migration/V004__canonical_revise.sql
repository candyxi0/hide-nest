-- Extend the single canonical publication receipt without copying CREATE history.
ALTER TABLE memory.revision DROP CONSTRAINT revision_revision_no_check;
ALTER TABLE memory.revision ADD CONSTRAINT revision_revision_no_check CHECK (revision_no >= 1);
ALTER TABLE memory.record DROP CONSTRAINT record_participation_state_check;
ALTER TABLE memory.record ADD CONSTRAINT record_participation_state_check
    CHECK (participation_state IN ('ACTIVE', 'SUPERSEDED'));
ALTER TABLE memory.create_receipt_item DROP CONSTRAINT create_receipt_item_record_id_key;
ALTER TABLE memory.create_receipt_item ADD COLUMN action text COLLATE "C" NOT NULL DEFAULT 'CREATE';
ALTER TABLE memory.create_receipt_item ADD COLUMN expected_revision_id uuid;
ALTER TABLE memory.create_receipt_item ADD CONSTRAINT receipt_item_action_check
    CHECK ((action = 'CREATE' AND expected_revision_id IS NULL)
        OR (action = 'REVISE' AND expected_revision_id IS NOT NULL));
ALTER TABLE memory.create_receipt_item ADD CONSTRAINT receipt_item_expected_revision_fk
    FOREIGN KEY (record_id, expected_revision_id) REFERENCES memory.revision(record_id, revision_id);
CREATE UNIQUE INDEX create_receipt_item_revise_target
    ON memory.create_receipt_item(record_id, expected_revision_id) WHERE action = 'REVISE';
ALTER TABLE memory.projection_outbox DROP CONSTRAINT projection_outbox_event_kind_check;
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_event_kind_check
    CHECK (event_kind IN ('MEMORY_CREATED', 'MEMORY_REVISED'));
ALTER TABLE memory.projection_outbox ADD COLUMN previous_revision_id uuid;
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_previous_check
    CHECK ((event_kind = 'MEMORY_CREATED' AND previous_revision_id IS NULL)
        OR (event_kind = 'MEMORY_REVISED' AND previous_revision_id IS NOT NULL));
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_previous_fk
    FOREIGN KEY (record_id, previous_revision_id) REFERENCES memory.revision(record_id, revision_id);
GRANT UPDATE (current_revision_id) ON memory.record TO hide_nest_api;
