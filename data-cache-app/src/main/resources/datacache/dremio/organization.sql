-- Dremio source SQL for the "organization" dataset.
-- Runs against Dremio via Arrow Flight SQL during refresh only. Never runs against DuckDB.
-- EXAMPLE: replace "org.organization_data" with your real Dremio space/table path before use.
SELECT
    cost_center,
    business_unit,
    cio,
    department
FROM org.organization_data
