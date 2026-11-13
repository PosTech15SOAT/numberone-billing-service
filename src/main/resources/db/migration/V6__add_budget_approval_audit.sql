ALTER TABLE budget
    ADD COLUMN approval_origin VARCHAR(20),
    ADD COLUMN approved_by_employee_id VARCHAR(255),
    ADD CONSTRAINT ck_budget_approval_audit CHECK (
        (approval_origin IS NULL AND approved_by_employee_id IS NULL)
        OR (status = 'APPROVED' AND approval_origin IS NOT NULL AND approval_origin = 'IN_PERSON'
            AND approved_by_employee_id IS NOT NULL AND length(btrim(approved_by_employee_id)) > 0)
        OR (status = 'APPROVED' AND approval_origin IS NOT NULL AND approval_origin = 'REMOTE' AND approved_by_employee_id IS NULL)
    );
