-- A task may replace several distinct predecessors; each predecessor and successor
-- remains one-to-one, with the original task provenance retained.
ALTER TABLE memory.record_succession DROP CONSTRAINT record_succession_task_id_key;
