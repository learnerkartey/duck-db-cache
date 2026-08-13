# 14. Validation

Every configured rule must pass before a BUILDING version is allowed to activate. Implemented in
`DatasetValidationService`, called by `RefreshCoordinator` between `VALIDATING` and activation.

## Minimum row count

```yaml
validation:
  minimum-row-count: 1000000
```

`0` (the default) disables the check. A build with fewer rows fails validation - useful for
catching a Dremio source query that silently returned a truncated or empty result.

## Required columns

```yaml
validation:
  required-columns:
    - fiscal_year
    - cost_center
```

Checked case-insensitively against the Arrow schema's actual column names (via the writer's
recorded column list) - catches a source SQL change that accidentally dropped a column consumers
depend on.

## Custom SQL validation

```yaml
validation:
  sql: classpath:datacache/validation/financial.sql
```

```sql
-- Must return exactly one row with one truthy/falsy column.
SELECT COUNT(*) = 0 AS is_valid
FROM financial
WHERE actual_amount < 0
```

Executed **read-only** against the BUILDING version's `.duckdb` file (opened
`duckdb.read_only=true`) after it is fully written. The result is interpreted as:

- `Boolean` -> used directly
- Numeric -> truthy if non-zero
- `null` or zero rows -> validation failure
- anything else -> truthy (non-null value)

## Failure behavior

All configured checks run; failures accumulate into a single `ValidationOutcome` with a combined
summary message, persisted to `dataset_versions.error_summary` and `validation_status = FAILED`.
The BUILDING file is deleted, the ACTIVE version is never touched, and the refresh result reports
`outcome: VALIDATION_FAILED`. See `RefreshCoordinatorTest.validationFailureDoesNotActivateNewVersion`
for a direct test.

Validation failures are **never retried** - they represent a data-quality problem the retry policy
cannot fix by trying again.
