-- Dremio source SQL for the "headcount" dataset.
-- Runs against Dremio via Arrow Flight SQL during refresh only. Never runs against DuckDB.
-- EXAMPLE: replace "hr.headcount_data" with your real Dremio space/table path before use.
SELECT
    cost_center,
    fiscal_year,
    fiscal_month,
    headcount_count
FROM hr.headcount_data
