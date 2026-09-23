-- A successor is a new identity. Preserve the predecessor revision's actual owner.
ALTER TABLE memory.create_receipt_item ADD COLUMN predecessor_record_id uuid;
UPDATE memory.create_receipt_item SET predecessor_record_id=record_id WHERE action='REVISE';
ALTER TABLE memory.create_receipt_item DROP CONSTRAINT receipt_item_expected_revision_fk;
ALTER TABLE memory.create_receipt_item DROP CONSTRAINT receipt_item_action_check;
ALTER TABLE memory.create_receipt_item ADD CONSTRAINT receipt_item_action_check CHECK (
    (action='CREATE' AND predecessor_record_id IS NULL AND expected_revision_id IS NULL)
    OR (action='REVISE' AND predecessor_record_id IS NOT NULL AND predecessor_record_id=record_id
        AND expected_revision_id IS NOT NULL)
    OR (action='SUPERSEDE' AND predecessor_record_id IS NOT NULL AND predecessor_record_id<>record_id
        AND expected_revision_id IS NOT NULL));
ALTER TABLE memory.create_receipt_item ADD CONSTRAINT receipt_item_predecessor_fk
    FOREIGN KEY (predecessor_record_id,expected_revision_id)
    REFERENCES memory.revision(record_id,revision_id);
ALTER TABLE memory.create_receipt_item ADD CONSTRAINT receipt_item_successor_fk
    FOREIGN KEY (record_id,revision_id) REFERENCES memory.revision(record_id,revision_id);
CREATE UNIQUE INDEX receipt_item_supersede_target
    ON memory.create_receipt_item(predecessor_record_id,expected_revision_id) WHERE action='SUPERSEDE';

ALTER TABLE memory.projection_outbox ADD COLUMN predecessor_record_id uuid;
UPDATE memory.projection_outbox SET predecessor_record_id=record_id WHERE event_kind='MEMORY_REVISED';
ALTER TABLE memory.projection_outbox DROP CONSTRAINT projection_outbox_previous_fk;
ALTER TABLE memory.projection_outbox DROP CONSTRAINT projection_outbox_previous_check;
ALTER TABLE memory.projection_outbox DROP CONSTRAINT projection_outbox_event_kind_check;
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_event_kind_check
    CHECK (event_kind IN ('MEMORY_CREATED','MEMORY_REVISED','MEMORY_SUPERSEDED'));
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_previous_check CHECK (
    (event_kind='MEMORY_CREATED' AND predecessor_record_id IS NULL AND previous_revision_id IS NULL)
    OR (event_kind='MEMORY_REVISED' AND predecessor_record_id IS NOT NULL
        AND predecessor_record_id=record_id AND previous_revision_id IS NOT NULL)
    OR (event_kind='MEMORY_SUPERSEDED' AND predecessor_record_id IS NOT NULL
        AND predecessor_record_id<>record_id AND previous_revision_id IS NOT NULL));
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_predecessor_fk
    FOREIGN KEY (predecessor_record_id,previous_revision_id)
    REFERENCES memory.revision(record_id,revision_id);
ALTER TABLE memory.projection_outbox ADD CONSTRAINT projection_outbox_successor_fk
    FOREIGN KEY (record_id,revision_id) REFERENCES memory.revision(record_id,revision_id);

CREATE TABLE memory.record_succession (
    predecessor_record_id uuid PRIMARY KEY REFERENCES memory.record(record_id),
    predecessor_revision_id uuid NOT NULL,
    successor_record_id uuid NOT NULL UNIQUE REFERENCES memory.record(record_id),
    successor_revision_id uuid NOT NULL UNIQUE,
    task_id uuid NOT NULL UNIQUE REFERENCES runtime.formation_task(task_id),
    created_at timestamptz NOT NULL,
    CHECK (predecessor_record_id<>successor_record_id),
    FOREIGN KEY (predecessor_record_id,predecessor_revision_id)
        REFERENCES memory.revision(record_id,revision_id),
    FOREIGN KEY (successor_record_id,successor_revision_id)
        REFERENCES memory.revision(record_id,revision_id)
);
REVOKE ALL ON memory.record_succession FROM PUBLIC,hide_nest_worker;
GRANT SELECT,INSERT ON memory.record_succession TO hide_nest_api;
GRANT UPDATE (participation_state) ON memory.record TO hide_nest_api;
