-- Analytical SQL for the "financial-summary" query.
-- Runs entirely inside DuckDB against the cached "financial" table - never touches Dremio.
SELECT
    fiscal_year,
    fiscal_month,
    SUM(actual_amount) AS actual,
    SUM(forecast_amount) AS forecast,
    SUM(actual_amount - forecast_amount) AS variance
FROM financial
WHERE fiscal_year = :fiscalYear
GROUP BY
    fiscal_year,
    fiscal_month
ORDER BY
    fiscal_month
