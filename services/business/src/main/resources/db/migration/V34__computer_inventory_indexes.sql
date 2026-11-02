CREATE INDEX computer_controls_active_page_idx ON computer_controls(tenant_id,state,attempt_id);
CREATE INDEX computer_controls_node_page_idx ON computer_controls(tenant_id,node_id,state,attempt_id);
