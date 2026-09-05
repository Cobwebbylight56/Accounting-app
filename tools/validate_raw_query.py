"""Check the dynamically built search query — Room cannot validate @RawQuery,
so an ambiguous or misspelled column there would only fail on a real phone."""
import os, re, sqlite3, sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src/main/java/com/rhys/financetracker/data/local")
ENT = os.path.join(ROOT, "entity")
con = sqlite3.connect(":memory:")
for fn in sorted(os.listdir(ENT)):
    src = open(os.path.join(ENT, fn)).read()
    table = re.search(r'tableName\s*=\s*"([^"]+)"', src).group(1)
    body = re.search(r'data class \w+\((.*?)\n\)', src, re.S).group(1)
    cols = []
    for line in body.split("\n"):
        cm = re.search(r'\bva[lr]\s+(\w+)\s*:', line.strip())
        if not cm: continue
        ci = re.search(r'@ColumnInfo\(\s*name\s*=\s*"([^"]+)"', line)
        cols.append((ci.group(1) if ci else cm.group(1), "@PrimaryKey" in line))
    con.execute('CREATE TABLE "%s" (%s)' % (table, ", ".join(
        '"%s"%s' % (c, " INTEGER PRIMARY KEY" if pk else "") for c, pk in cols)))

dao = open(os.path.join(ROOT, "dao", "TransactionDao.kt")).read()
consts = dict(re.findall(r'const val (\w+) = """(.*?)"""', dao, re.S))
COLUMNS, JOINS = consts["DETAIL_COLUMNS"], consts["DETAIL_JOINS"]

qsrc = open(os.path.join(ROOT, "dao", "TransactionQuery.kt")).read()
sorts = re.findall(r'\w+\("[^"]*",\s*"([^"]+)"\)', qsrc)
assert sorts, "no sort orders parsed"

# Every condition the builder can emit, with two IDs per IN list.
ph = "?, ?"
conditions = [
    "t.is_archived = 0",
    "(t.description LIKE ? COLLATE NOCASE OR IFNULL(t.notes, '') LIKE ? COLLATE NOCASE"
    " OR IFNULL(t.tags, '') LIKE ? COLLATE NOCASE OR IFNULL(a.name, '') LIKE ? COLLATE NOCASE"
    " OR IFNULL(c.name, '') LIKE ? COLLATE NOCASE OR IFNULL(p.name, '') LIKE ? COLLATE NOCASE)",
    "t.date >= ?", "t.date <= ?",
    f"(t.account_id IN ({ph}) OR t.transfer_account_id IN ({ph}))",
    "t.category_id IS NULL",
    f"t.category_id IN ({ph})",
    f"COALESCE(t.person_id, a.person_id) IN ({ph})",
    f"t.type IN ({ph})",
    "t.amount_minor >= ?", "t.amount_minor <= ?",
    "t.savings_goal_id = ?", "t.recurring_rule_id = ?",
    "t.is_confirmed = 0", "t.is_cleared = 0",
]

failures = 0
for label, where in (("no filters", ""),
                     ("every filter", "WHERE " + " AND ".join(conditions))):
    for order in sorts:
        sql = f"SELECT {COLUMNS}\n{JOINS}\n{where}\nORDER BY {order}\nLIMIT 50"
        n = sql.count("?")
        try:
            con.execute("EXPLAIN " + sql, [None] * n)
        except Exception as e:
            failures += 1
            print(f"FAIL [{label}] ORDER BY {order}\n     {type(e).__name__}: {e}")
print(f"raw search query: {2 * len(sorts)} shapes checked, {failures} failed")
sys.exit(1 if failures else 0)
