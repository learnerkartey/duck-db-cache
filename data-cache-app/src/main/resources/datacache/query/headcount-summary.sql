-- Analytical SQL for the "headcount-summary" query.
-- Joins the cached "headcount" and "organization" tables entirely inside DuckDB.
SELECT
    o.cio,
    o.department,
    h.fiscal_year,
    h.fiscal_month,
    SUM(h.headcount_count) AS total_headcount
FROM headcount h
JOIN organization o
    ON h.cost_center = o.cost_center
WHERE h.fiscal_year = :fiscalYear
GROUP BY
    o.cio,
    o.department,
    h.fiscal_year,
    h.fiscal_month
ORDER BY
    o.cio,
    o.department,
    h.fiscal_month
