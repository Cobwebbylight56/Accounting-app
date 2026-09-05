"""Check that project types used in a file are actually imported.

The companion check (that every import resolves) does not catch the opposite
mistake: referring to a project type without importing it. That costs a full CI
round trip for what is a one-line fix, so it is checked here in a second.

Only unambiguous cases are reported: a type declared exactly once in the
project, used in a file that neither declares it nor imports it.
"""
import os
import re
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app/src")
ROOTS = [os.path.join(ROOT, "main/java"), os.path.join(ROOT, "test/java")]

MOD = (r'(?:public |internal |private |protected |abstract |open |sealed |data |enum '
       r'|annotation |value |inline |suspend |external |operator |infix |tailrec '
       r'|const |lateinit |expect |actual )*')

files = [os.path.join(dp, fn)
         for r in ROOTS for dp, _, fns in os.walk(r) for fn in fns if fn.endswith(".kt")]

# Where each top-level type is declared. Nested types are skipped: they are
# referenced through their outer name, which is what gets imported.
declared: dict[str, list[str]] = {}
package_of: dict[str, str] = {}
for f in files:
    src = open(f).read()
    pm = re.search(r'^package\s+([\w.]+)', src, re.M)
    if not pm:
        continue
    package_of[f] = pm.group(1)
    for m in re.finditer(r'^(?:@[\w.]+(?:\([^\n]*\))?\s*)*' + MOD +
                         r'(?:class|interface|object|typealias)\s+(\w+)', src, re.M):
        declared.setdefault(m.group(1), []).append(pm.group(1))

unique = {name: pkgs[0] for name, pkgs in declared.items() if len(set(pkgs)) == 1}

problems = []
for f in files:
    src = open(f).read()
    pkg = package_of.get(f)
    if pkg is None:
        continue
    imported = set()
    for line in src.split("\n"):
        m = re.match(r'\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?', line)
        if m:
            imported.add(m.group(2) or m.group(1).rsplit(".", 1)[-1])
            if m.group(1).endswith(".*"):
                imported.add("*" + m.group(1)[:-2])

    body = "\n".join(l for l in src.split("\n") if not l.startswith(("import ", "package ")))
    body = re.sub(r'"""(?:.|\n)*?"""', '""', body)
    body = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', body)
    body = re.sub(r'//[^\n]*', '', body)
    body = re.sub(r'/\*(?:.|\n)*?\*/', '', body)

    own = {m.group(1) for m in re.finditer(
        r'^(?:@[\w.]+(?:\([^\n]*\))?\s*)*' + MOD +
        r'(?:class|interface|object|typealias)\s+(\w+)', src, re.M)}
    nested = {m.group(1) for m in re.finditer(
        r'^\s+(?:@[\w.]+\s*)*' + MOD + r'(?:class|interface|object)\s+(\w+)', src, re.M)}

    # A fully-qualified use needs no import, so ignore any occurrence with a
    # dotted prefix — `com.rhys.financetracker.di.IoDispatcher` and the like.
    bare = set(re.findall(r'(?<![.\w])([A-Z][A-Za-z0-9_]*)\b', body))

    for name in sorted(bare):
        if name not in unique:
            continue
        target = unique[name]
        if target == pkg or name in imported or name in own or name in nested:
            continue
        if any(k.startswith("*") and target == k[1:] for k in imported):
            continue
        line = next((i for i, l in enumerate(src.split("\n"), 1)
                     if re.search(r'(?<![.\w])' + name + r'\b', l)
                     and not l.startswith(("import ", "package "))), 0)
        problems.append((f, line, name, target))

rel = lambda p: os.path.relpath(p, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
print(f"{len(files)} files, {len(unique)} uniquely-named project types")
if problems:
    print(f"\n{len(problems)} types used without an import:")
    for f, line, name, target in problems:
        print(f"  {rel(f)}:{line}  {name}  (declared in {target})")
else:
    print("every project type used is imported or local")
sys.exit(1 if problems else 0)
