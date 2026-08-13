-- Dremio source SQL for the "financial" dataset.
-- Runs against Dremio via Arrow Flight SQL during refresh only. Never runs against DuckDB.
-- EXAMPLE: replace "finance.financial_data" with your real Dremio space/table path before use.
SELECT
    fiscal_year,
    fiscal_month,
    business_unit,
    cost_center,
    account,
    actual_amount,
    forecast_amount
FROM finance.financial_data
