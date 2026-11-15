#!/bin/sh
# Também pode ser executado explicitamente para recuperar bootstrap parcial.
set -eu
: "${POSTGRES_PASSWORD:?Senha administrativa obrigatória}"
: "${OS_PASSWORD:?Senha OS obrigatória}"
: "${BILLING_PASSWORD:?Senha Billing obrigatória}"
: "${INVENTORY_PASSWORD:?Senha Inventory obrigatória}"

psql -X --username postgres --dbname postgres --set ON_ERROR_STOP=1 <<'SQL'
\getenv os_password OS_PASSWORD
\getenv billing_password BILLING_PASSWORD
\getenv inventory_password INVENTORY_PASSWORD
SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION', role_name)
FROM (VALUES ('os_user'), ('billing_user'), ('inventory_user')) AS roles(role_name)
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = role_name) \gexec
ALTER ROLE os_user PASSWORD :'os_password';
ALTER ROLE billing_user PASSWORD :'billing_password';
ALTER ROLE inventory_user PASSWORD :'inventory_password';
SELECT format('CREATE DATABASE %I OWNER %I', db_name, owner_name)
FROM (VALUES ('service_order_db', 'os_user'), ('numberone_billing', 'billing_user'), ('inventory_db', 'inventory_user')) AS databases(db_name, owner_name)
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = db_name) \gexec
REVOKE ALL ON DATABASE service_order_db, numberone_billing, inventory_db, postgres, template1 FROM PUBLIC;
\connect service_order_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO os_user;
\connect numberone_billing
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO billing_user;
\connect inventory_db
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO inventory_user;
SQL
