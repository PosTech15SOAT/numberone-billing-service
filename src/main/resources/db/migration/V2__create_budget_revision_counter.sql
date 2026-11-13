CREATE TABLE budget_revision_counter (
    service_order_id UUID PRIMARY KEY,
    last_version INTEGER NOT NULL,
    CONSTRAINT ck_budget_revision_counter_positive CHECK (last_version > 0)
);

INSERT INTO budget_revision_counter (service_order_id, last_version)
SELECT service_order_id, MAX(budget_version)
FROM budget
GROUP BY service_order_id;
