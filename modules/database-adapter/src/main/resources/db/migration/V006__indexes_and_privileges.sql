CREATE INDEX proposal_target_memory_lookup
    ON memory.proposal (target_memory_id)
    WHERE target_memory_id IS NOT NULL;
CREATE INDEX proposal_revision_proposal_lookup
    ON memory.proposal_revision (proposal_id, revision_no);
CREATE INDEX review_member_proposal_lookup
    ON memory.review_member (proposal_revision_id);
CREATE INDEX decision_proposal_lookup
    ON memory.decision (proposal_revision_id)
    WHERE proposal_revision_id IS NOT NULL;
CREATE INDEX decision_target_lookup
    ON memory.decision (target_kind, target_id, target_revision_ref);
CREATE INDEX access_policy_grant_lookup
    ON memory.access_policy_grant (actor_role, purpose, effect, object_scope);
CREATE INDEX memory_record_state_lookup
    ON memory.memory_record (state, memory_id);
CREATE INDEX memory_record_policy_lookup
    ON memory.memory_record (policy_id, current_policy_revision_no);
CREATE INDEX memory_revision_created_lookup
    ON memory.memory_revision (memory_id, revision_no DESC);
CREATE INDEX change_event_target_lookup
    ON memory.change_event (target_kind, target_id, target_revision_ref, sequence_no);
CREATE INDEX outbox_event_ready_lookup
    ON runtime.outbox_event (available_at, sequence_no)
    WHERE state = 'READY';
CREATE INDEX outbox_event_lease_lookup
    ON runtime.outbox_event (lease_until, sequence_no)
    WHERE state = 'LEASED';
CREATE INDEX outbox_event_aggregate_lookup
    ON runtime.outbox_event (aggregate_kind, aggregate_id, aggregate_revision);
CREATE INDEX outbox_event_idempotency_lookup
    ON runtime.outbox_event (idempotency_key);

REVOKE ALL ON ALL TABLES IN SCHEMA memory, runtime FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA memory, runtime FROM PUBLIC;

-- B08: Revoke PUBLIC EXECUTE on all functions in formal schemas
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA memory FROM PUBLIC;
REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA runtime FROM PUBLIC;

GRANT USAGE ON SCHEMA memory, runtime TO hide_nest_api, hide_nest_worker;

GRANT SELECT ON ALL TABLES IN SCHEMA memory, runtime TO hide_nest_api;
GRANT INSERT ON
    memory.actor_ref,
    memory.proposal,
    memory.proposal_revision,
    memory.review_session,
    memory.review_member,
    memory.decision,
    memory.access_policy,
    memory.access_policy_revision,
    memory.access_policy_grant,
    memory.memory_record,
    memory.memory_revision,
    memory.change_event,
    runtime.idempotency_receipt,
    runtime.outbox_event
TO hide_nest_api;
GRANT UPDATE (state, current_revision_id, policy_id, current_policy_revision_no, updated_at)
    ON memory.memory_record TO hide_nest_api;
GRANT UPDATE (state, terminal_at)
    ON memory.review_session TO hide_nest_api;
GRANT UPDATE (current_revision_no)
    ON memory.access_policy TO hide_nest_api;

GRANT SELECT ON ALL TABLES IN SCHEMA memory, runtime TO hide_nest_worker;
GRANT INSERT ON runtime.idempotency_receipt, runtime.outbox_event TO hide_nest_worker;
GRANT UPDATE (
    state, available_at, lease_owner, lease_until, attempt_count,
    last_failure_code, completed_at
) ON runtime.outbox_event TO hide_nest_worker;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA memory, runtime
    TO hide_nest_api, hide_nest_worker;

-- B08: Grant EXECUTE only to migrator (owner); API/Worker get no function access
-- No GRANT EXECUTE to hide_nest_api or hide_nest_worker;
-- migrator owns the functions and retains implicit EXECUTE.
