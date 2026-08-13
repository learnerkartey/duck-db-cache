# 12. Adding a New Dataset

Worked example: adding an `expense` dataset. **No Java class of any kind is required** - no new
Entity, Repository, Loader, Scheduler, Service, or Controller.

## 1. Create the source SQL

`src/main/resources/datacache/dremio/expense.sql`:

```sql
SELECT
    fiscal_year,
    fiscal_month,
    cost_center,
    expense_category,
    expense_amount
FROM finance.expense_data
```

## 2. Register the dataset

`src/main/resources/application.yml`:

```yaml
data-cache:
  datasets:
    expense:
      enabled: true
      table-name: expense
      source-sql: classpath:datacache/dremio/expense.sql
      startup:
        mode: USE_EXISTING_OR_CREATE
      refresh-cron: "0 45 1,7,13,19 * * *"
      retry:
        max-attempts: 3
        initial-delay: 10s
        multiplier: 2
        max-delay: 60s
      validation:
        minimum-row-count: 1
        required-columns:
          - fiscal_year
          - cost_center
          - expense_amount
```

## 3. Restart the app (or trigger a refresh manually)

As soon as the application restarts with this configuration, the mandatory startup auto-create
rule notices `expense` has no ACTIVE cache yet and loads it automatically - no manual step is
required. See [23-STARTUP-CACHE-LIFECYCLE.md](23-STARTUP-CACHE-LIFECYCLE.md). If you'd rather not
wait for a restart:

```bash
curl -X POST localhost:8080/api/v1/cache/admin/datasets/expense/refresh
```

Subsequent refreshes happen on its `refresh-cron` schedule - `DynamicRefreshScheduler` picks up
any newly configured dataset automatically on the next application restart.

## 4. Verify

```bash
curl localhost:8080/api/v1/cache/admin/datasets/expense
```

## 5. Use it in a query

```sql
SELECT cost_center, SUM(expense_amount) AS total_expense
FROM expense
WHERE fiscal_year = :fiscalYear
GROUP BY cost_center
```

Register per [13-ADDING-A-NEW-QUERY.md](13-ADDING-A-NEW-QUERY.md). That's the entire process.
