# Developer tools

Small helpers that are not part of the app.

## `validate_queries.py` and `validate_raw_query.py`

Room compiles every `@Query` against the real schema, so a bad one fails the
build — but only on CI, minutes later. These two scripts do the same check in
about a second:

```bash
python3 tools/validate_queries.py     # every @Query in the DAOs
python3 tools/validate_raw_query.py   # the dynamic search query builder
```

They read the `@Entity` classes, build the matching tables in an in-memory
SQLite database, and ask SQLite to prepare each statement. Anything SQLite
rejects — a misspelled column, an ambiguous name in a join, a syntax slip —
is reported with its file and line.

The second script matters more than it looks: the search screen builds its SQL
at runtime with `@RawQuery`, which Room cannot check at all, so without this
a mistake there would surface only on a phone.

Nothing is written and no real database is touched.
