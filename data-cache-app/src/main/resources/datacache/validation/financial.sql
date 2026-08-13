-- Custom validation SQL for the "financial" dataset, executed read-only against the BUILDING
-- version's DuckDB file before it is allowed to become ACTIVE. Must return exactly one row with a
-- single truthy/falsy column; a falsy result fails validation and the current ACTIVE version is
-- left untouched.
--
-- Example check: every row must have a non-negative actual_amount.
SELECT COUNT(*) = 0 AS is_valid
FROM financial
WHERE actual_amount < 0
