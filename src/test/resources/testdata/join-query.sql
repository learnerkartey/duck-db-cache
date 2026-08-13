SELECT
    o.department AS department,
    SUM(f.amount) AS total_amount
FROM financial f
JOIN organization o ON f.cost_center = o.cost_center
WHERE f.fiscal_year = :fiscalYear
GROUP BY o.department
ORDER BY o.department
