"""Validate every Room @Query against a real SQLite schema built from the entities.

Room compiles the SQL for us on CI; this does the same check locally so a bad
query costs seconds instead of a whole CI round trip.
"""
import os, re, sqlite3, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/java/com/rhys/financetracker/data/local")
ENT = os.path.join(ROOT, "entity")
DAO = os.path.join(ROOT, "dao")

# ---------- 1. entities -> CREATE TABLE ----------
tables = {}
for fn in sorted(os.listdir(ENT)):
    src = open(os.path.join(ENT, fn)).read()
    m = re.search(r'tableName\s*=\s*"([^"]+)"', src)
    if not m:
        print(f"!! no tableName in {fn}"); continue
    table = m.group(1)
    # constructor block of the data class
    dm = re.search(r'data class \w+\((.*?)\n\)', src, re.S)
    if not dm:
        print(f"!! no constructor in {fn}"); continue
    body = dm.group(1)
    cols = []
    for line in body.split("\n"):
        line = line.strip()
        if not line or line.startswith("//") or line.startswith("*") or line.startswith("/*"):
            continue
        cm = re.search(r'\bva[lr]\s+(\w+)\s*:', line)
        if not cm:
            continue
        field = cm.group(1)
        ci = re.search(r'@ColumnInfo\(\s*name\s*=\s*"([^"]+)"', line)
        col = ci.group(1) if ci else field
        if not ci and col != col.lower():
            print(f"?? {fn}: field `{field}` has no @ColumnInfo and is not lowercase")
        pk = "@PrimaryKey" in line
        cols.append((col, pk))
    tables[table] = cols

con = sqlite3.connect(":memory:")
for table, cols in tables.items():
    defs = ", ".join(f'"{c}"' + (" INTEGER PRIMARY KEY" if pk else "") for c, pk in cols)
    con.execute(f'CREATE TABLE "{table}" ({defs})')
print(f"built {len(tables)} tables: {', '.join(sorted(tables))}\n")

# ---------- 2. DAOs -> queries ----------
QUERY_RE = re.compile(r'@Query\(\s*(.*?)\s*\)\s*\n\s*(?:@\w+[^\n]*\n\s*)*(?:suspend\s+)?fun\s+(\w+)', re.S)

def literal(blob):
    """Turn the Kotlin string expression after @Query( into plain SQL."""
    blob = blob.strip().rstrip(",").strip()
    if blob.startswith('"""'):
        return blob[3:blob.rindex('"""')]
    # concatenated "..." + "..." pieces
    parts = re.findall(r'"((?:[^"\\]|\\.)*)"', blob)
    return " ".join(p.replace('\\"', '"') for p in parts)

failures = 0
checked = 0
for fn in sorted(os.listdir(DAO)):
    path = os.path.join(DAO, fn)
    src = open(path).read()
    # Room resolves `const val` interpolations at compile time; do the same.
    consts = dict(re.findall(r'const val (\w+) = """(.*?)"""', src, re.S))
    for blob, name in QUERY_RE.findall(src):
        sql = literal(blob)
        for k, v in consts.items():
            sql = sql.replace("$" + k, v)
        if "${" in sql:            # dynamically built, not statically checkable
            print(f"-- skipped (dynamic): {fn}::{name}")
            continue
        params = {p: None for p in re.findall(r'(?<![:\w]):(\w+)', sql)}
        checked += 1
        try:
            con.execute("EXPLAIN " + sql, params)
        except Exception as e:
            failures += 1
            line = src[:src.index(blob)].count("\n") + 1
            print(f"\nFAIL {fn}:{line} :: {name}\n     {type(e).__name__}: {e}")

print(f"\nchecked {checked} queries, {failures} failed")
sys.exit(1 if failures else 0)
