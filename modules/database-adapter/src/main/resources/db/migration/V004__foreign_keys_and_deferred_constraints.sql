ALTER TABLE memory.proposal
    ADD CONSTRAINT proposal_target_memory_fk
    FOREIGN KEY (target_memory_id)
    REFERENCES memory.memory_record (memory_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.proposal_revision
    ADD CONSTRAINT proposal_revision_proposal_fk
    FOREIGN KEY (proposal_id)
    REFERENCES memory.proposal (proposal_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.proposal_revision
    ADD CONSTRAINT proposal_revision_actor_fk
    FOREIGN KEY (perspective_actor_id)
    REFERENCES memory.actor_ref (actor_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.proposal_revision
    ADD CONSTRAINT proposal_revision_expected_memory_fk
    FOREIGN KEY (expected_memory_revision_id)
    REFERENCES memory.memory_revision (memory_revision_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.review_member
    ADD CONSTRAINT review_member_session_fk
    FOREIGN KEY (review_session_id)
    REFERENCES memory.review_session (review_session_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.review_member
    ADD CONSTRAINT review_member_proposal_revision_fk
    FOREIGN KEY (proposal_revision_id)
    REFERENCES memory.proposal_revision (proposal_revision_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.decision
    ADD CONSTRAINT decision_actor_fk
    FOREIGN KEY (actor_id)
    REFERENCES memory.actor_ref (actor_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.decision
    ADD CONSTRAINT decision_proposal_revision_fk
    FOREIGN KEY (proposal_revision_id)
    REFERENCES memory.proposal_revision (proposal_revision_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.decision
    ADD CONSTRAINT decision_review_session_fk
    FOREIGN KEY (review_session_id)
    REFERENCES memory.review_session (review_session_id)
    ON DELETE NO ACTION;

CREATE UNIQUE INDEX decision_final_verdict_unique
    ON memory.decision (review_session_id, proposal_revision_id)
    WHERE decision_kind IN ('USER_CONFIRM', 'USER_REJECT', 'USER_DEFER');

ALTER TABLE memory.access_policy_revision
    ADD CONSTRAINT access_policy_revision_policy_fk
    FOREIGN KEY (policy_id)
    REFERENCES memory.access_policy (policy_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.access_policy_revision
    ADD CONSTRAINT access_policy_revision_decision_fk
    FOREIGN KEY (created_by_decision_id)
    REFERENCES memory.decision (decision_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.access_policy
    ADD CONSTRAINT access_policy_current_revision_fk
    FOREIGN KEY (policy_id, current_revision_no)
    REFERENCES memory.access_policy_revision (policy_id, revision_no)
    ON DELETE NO ACTION
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE memory.access_policy_grant
    ADD CONSTRAINT access_policy_grant_revision_fk
    FOREIGN KEY (policy_id, revision_no)
    REFERENCES memory.access_policy_revision (policy_id, revision_no)
    ON DELETE NO ACTION;

ALTER TABLE memory.memory_revision
    ADD CONSTRAINT memory_revision_memory_fk
    FOREIGN KEY (memory_id)
    REFERENCES memory.memory_record (memory_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.memory_revision
    ADD CONSTRAINT memory_revision_actor_fk
    FOREIGN KEY (perspective_actor_id)
    REFERENCES memory.actor_ref (actor_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.memory_revision
    ADD CONSTRAINT memory_revision_decision_fk
    FOREIGN KEY (created_by_decision_id)
    REFERENCES memory.decision (decision_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.memory_record
    ADD CONSTRAINT memory_record_current_revision_fk
    FOREIGN KEY (memory_id, current_revision_id)
    REFERENCES memory.memory_revision (memory_id, memory_revision_id)
    ON DELETE NO ACTION
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE memory.memory_record
    ADD CONSTRAINT memory_record_current_policy_fk
    FOREIGN KEY (policy_id, current_policy_revision_no)
    REFERENCES memory.access_policy_revision (policy_id, revision_no)
    ON DELETE NO ACTION
    DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE memory.change_event
    ADD CONSTRAINT change_event_event_type_fk
    FOREIGN KEY (event_type)
    REFERENCES runtime.event_type_registry (event_type)
    ON DELETE NO ACTION;

ALTER TABLE memory.change_event
    ADD CONSTRAINT change_event_actor_fk
    FOREIGN KEY (actor_id)
    REFERENCES memory.actor_ref (actor_id)
    ON DELETE NO ACTION;

ALTER TABLE memory.change_event
    ADD CONSTRAINT change_event_decision_fk
    FOREIGN KEY (decision_id)
    REFERENCES memory.decision (decision_id)
    ON DELETE NO ACTION;

ALTER TABLE runtime.outbox_event
    ADD CONSTRAINT outbox_event_type_fk
    FOREIGN KEY (event_type)
    REFERENCES runtime.event_type_registry (event_type)
    ON DELETE NO ACTION;

ALTER TABLE runtime.outbox_event
    ADD CONSTRAINT outbox_change_event_fk
    FOREIGN KEY (change_event_id)
    REFERENCES memory.change_event (change_event_id)
    ON DELETE NO ACTION;

ALTER TABLE runtime.outbox_event
    ADD CONSTRAINT outbox_failure_code_fk
    FOREIGN KEY (last_failure_code)
    REFERENCES runtime.failure_code_registry (failure_code)
    ON DELETE NO ACTION;
