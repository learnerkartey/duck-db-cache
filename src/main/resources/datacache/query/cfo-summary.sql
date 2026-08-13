-- Analytical SQL for the "cfo-summary" query.
-- Joins the cached "financial" and "organization" tables entirely inside DuckDB. The query engine
-- transparently attaches each dataset's currently ACTIVE version under these stable logical names -
-- never write versioned table names (e.g. financial_v11) or physical file paths here.
SELECT
    o.cio,
    o.business_unit,
    SUM(f.actual_amount) AS actual,
    SUM(f.forecast_amount) AS forecast,
    SUM(f.actual_amount - f.forecast_amount) AS variance
FROM financial f
JOIN organization o
    ON f.cost_center = o.cost_center
WHERE f.fiscal_year = :fiscalYear
GROUP BY
    o.cio,
    o.business_unit
ORDER BY
    o.cio,
    o.business_unit
