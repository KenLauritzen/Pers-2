"""Verification sweep for Focus.

Every check here exists because that exact class of mistake once reached a
build. The sandbox can't compile Android, so this is the last line before
GitHub Actions does.

Run from the project root:  python3 tools/check.py

Not part of the Android build; it doesn't need to be in the repo.
"""
import re, os, sys, xml.etree.ElementTree as ET

SRC = 'app/src/main/java/com/focusledger/timer'
RES = 'app/src/main/res'
A = '{http://schemas.android.com/apk/res/android}'
fails = []

files = {f: open(f'{SRC}/{f}').read() for f in sorted(os.listdir(SRC)) if f.endswith('.kt')}
layouts = {}
for root, _, fs in os.walk(RES + '/layout'):
    for f in fs:
        if f.endswith('.xml'):
            layouts[f] = open(os.path.join(root, f)).read()


def strip_comments(src):
    src = re.sub(r'/\*.*?\*/', lambda m: '\n' * m.group(0).count('\n'), src, flags=re.S)
    return re.sub(r'//[^\n]*', '', src)


def code_only(line):
    """A line with the text of string literals removed, keeping any $name or
    ${...} templates inside them, since those are real uses."""
    def keep_templates(m):
        return ' '.join(re.findall(r'\$\{[^}]*\}|\$\w+', m.group(0)))
    return re.sub(r'"(?:[^"\\]|\\.)*"', keep_templates, line)


def trailing_lambda_names(code):
    """Names that take a trailing lambda: `name {` or `name(...) {`, with the
    brackets matched properly so nested calls in the arguments don't hide it."""
    names = set(re.findall(r'(\w+)\s*\{', code))
    for m in re.finditer(r'(\w+)\s*\(', code):
        i, depth = m.end(), 1
        while i < len(code) and depth:
            if code[i] == '(': depth += 1
            elif code[i] == ')': depth -= 1
            i += 1
        if re.match(r'\s*\{', code[i:i + 40]):
            names.add(m.group(1))
    return names


def section(name):
    print(name)


def ok_if(prefix):
    print("   ok" if not any(x.startswith(prefix) for x in fails) else "")


def fn_bodies(src):
    """Yield (first line number, body text) for each function."""
    for m in re.finditer(r'\n[ \t]*(?:(?:private|override|inner|internal|suspend|inline)\s+)*fun\s+[^\n{]*\{', src):
        i, depth = m.end(), 1
        while i < len(src) and depth:
            if src[i] == '{': depth += 1
            elif src[i] == '}': depth -= 1
            i += 1
        yield src[:m.end()].count('\n') + 1, src[m.end():i]


# ---- braces ---------------------------------------------------------------
section("braces")
for f, s in files.items():
    c = strip_comments(s)
    if c.count('{') != c.count('}'):
        print(f"   FAIL {f}: {c.count('{')} open, {c.count('}')} close")
        fails.append(f'braces {f}')
ok_if('braces')

# ---- xml ------------------------------------------------------------------
section("xml")
for root, _, fs in os.walk(RES):
    for f in fs:
        if f.endswith('.xml'):
            try: ET.parse(os.path.join(root, f))
            except Exception as e:
                print(f"   FAIL {f}: {e}"); fails.append(f'xml {f}')
try: ET.parse('app/src/main/AndroidManifest.xml')
except Exception as e:
    print(f"   FAIL AndroidManifest.xml: {e}"); fails.append('xml manifest')
ok_if('xml')

# ---- conflicting declarations ---------------------------------------------
section("conflicting declarations")
for f, s in files.items():
    depth = paren = 0
    scopes = [[0, {}]]
    for i, line in enumerate(strip_comments(s).split('\n'), 1):
        header = re.search(r'\b(class|object|interface|fun)\b', line)
        m = re.match(r'\s*(?:private\s+|const\s+|lateinit\s+)*va[lr] (\w+)\s*[:=]', line)
        if m and not header and paren == 0:
            n = m.group(1)
            if n in scopes[-1][1]:
                print(f"   FAIL {f}:{i} '{n}' redeclared (first at {scopes[-1][1][n]})")
                fails.append(f'decl {f}:{i}')
            scopes[-1][1][n] = i
        paren = max(paren + line.count('(') - line.count(')'), 0)
        # Closes before opens: on "} else {" the two scopes are siblings.
        for _ in range(line.count('}')):
            if depth:
                depth -= 1
                while len(scopes) > 1 and scopes[-1][0] > depth: scopes.pop()
        for _ in range(line.count('{')):
            depth += 1; scopes.append([depth, {}])
ok_if('decl')

# ---- use before declaration ---------------------------------------------
# Walks back from each local declaration through the block that contains it.
# A use found before leaving that block can't see the declaration. Uses in a
# sibling block \u2014 the same name declared again in an else branch \u2014 are fine,
# which the first version of this check got wrong.
section("use before declaration")
for f, s_ in files.items():
    code = strip_comments(s_)
    members = set(re.findall(r'^    (?:private |override |lateinit |const )*va[lr] (\w+)', code, re.M))
    for base, body in fn_bodies(code):
        lines = body.split('\n')
        for i, line in enumerate(lines):
            m = re.match(r'\s*va[lr] (\w+)\s*[:=]', line)
            if not m or m.group(1) in members: continue
            n = m.group(1)
            nest = 0
            for j in range(i - 1, -1, -1):
                prev = code_only(lines[j])
                nest += prev.count('}') - prev.count('{')
                if nest < 0: break                       # left the declaring block
                if nest > 0: continue                    # an inner block has its own names
                if re.search(rf'(?<![\w.]){n}\b', prev) \
                        and not re.search(rf'va[lr] {n}\b|\b{n}\s*->|\b{n}\s*:|\b{n}\s*=[^=]', prev):
                    print(f"   FAIL {f}:{base + j} '{n}' used before its declaration at line {base + i}")
                    fails.append(f'before {f}:{base + j}')
                    break
ok_if('before')

# ---- string templates -----------------------------------------------------
section("string templates")
for f, s in files.items():
    code = strip_comments(s)
    declared = set(re.findall(r'\b(?:va[lr]|fun|class|object)\s+(\w+)', code))
    declared |= set(re.findall(r'[(,]\s*(?:va[lr]\s+)?(\w+)\s*:', code))      # parameters
    declared |= set(re.findall(r'\bfor\s*\((\w+)\s+in\b', code))
    declared |= set(re.findall(r'\{\s*(\w+)\s*->', code))
    declared |= set(re.findall(r'\{\s*\(?\s*(\w+)\s*,\s*(\w+)', code) and
                    [x for pair in re.findall(r'\{\s*\(?\s*(\w+)\s*,\s*(\w+)\s*\)?\s*->', code) for x in pair])
    # Inherited from Android's own classes rather than declared here.
    declared |= {'it', 'this', 'field', 'packageName', 'resources', 'intent',
                 'applicationContext', 'title', 'filesDir', 'cacheDir'}
    for i, line in enumerate(code.split('\n'), 1):
        for lit in re.findall(r'"(?:[^"\\]|\\.)*"', line):
            for name in re.findall(r'(?<!\\)\$([A-Za-z_]\w*)', lit):
                if name not in declared:
                    print(f'   FAIL {f}:{i} "${name}" resolves to no such name \u2014 did you mean ${{...}}?')
                    fails.append(f'tmpl {f}:{i}')
ok_if('tmpl')

# ---- escaped unicode ------------------------------------------------------
section("escaped unicode")
for f, s in files.items():
    for i, line in enumerate(s.split('\n'), 1):
        if re.search(r'"[^"]*\\\\u[0-9a-fA-F]{4}[^"]*"', line) and not line.strip().startswith('//'):
            print(f"   FAIL {f}:{i} double-escaped \\\\u \u2014 shows as text, not the character")
            fails.append(f'esc {f}:{i}')
ok_if('esc')

# ---- lambda return labels -------------------------------------------------
section("lambda return labels")
for f, s in files.items():
    for base, body in fn_bodies(strip_comments(s)):
        labels = trailing_lambda_names(body)
        for m in re.finditer(r'return@(\w+)', body):
            if m.group(1) not in labels:
                line = base + body[:m.start()].count('\n')
                print(f"   FAIL {f}:{line} return@{m.group(1)} names no enclosing lambda here")
                fails.append(f'label {f}:{line}')
ok_if('label')

# ---- touch release and cancel ---------------------------------------------
section("touch release and cancel kept separate")
for f, s in files.items():
    for m in re.finditer(r'MotionEvent\.ACTION_UP\s*,\s*MotionEvent\.ACTION_CANCEL', s):
        line = s[:m.start()].count('\n') + 1
        print(f"   FAIL {f}:{line} UP and CANCEL share a branch \u2014 a cancel would commit")
        fails.append(f'touch {f}:{line}')
ok_if('touch')

# ---- layout weights -------------------------------------------------------
section("layout weights add up")
for fname, text in layouts.items():
    tree = ET.fromstring(text)
    for el in tree.iter():
        total = el.get(A + 'weightSum')
        if total is None: continue
        got = round(sum(float(c.get(A + 'layout_weight', '0')) for c in list(el)), 3)
        if abs(got - float(total)) > 0.001:
            print(f"   FAIL {fname}: {el.get(A + 'id', '(unnamed)')} weightSum {float(total):g} "
                  f"but children add to {got:g}")
            fails.append(f'weight {fname}')
ok_if('weight')

# ---- ids ------------------------------------------------------------------
section("R.id references exist")
all_ids = set()
for text in layouts.values():
    all_ids |= set(re.findall(r'@\+id/(\w+)', text))
for f, s in files.items():
    for ref in sorted(set(re.findall(r'R\.id\.(\w+)', s))):
        if ref not in all_ids:
            print(f"   FAIL {f}: R.id.{ref}"); fails.append(f'id {f}.{ref}')
ok_if('id')

section("view-binding references exist")
for f, s in files.items():
    m = re.search(r'(\w+)Binding\.inflate', s)
    if not m: continue
    layout = re.sub(r'(?<!^)([A-Z])', r'_\1', m.group(1)).lower() + '.xml'
    if layout not in layouts: continue
    ids = set(re.findall(r'@\+id/(\w+)', layouts[layout]))
    var = re.search(r'(?:lateinit\s+)?va[lr]\s+(\w+)\s*:\s*\w+Binding', s)
    if not var: continue
    for ref in sorted(set(re.findall(rf'\b{var.group(1)}\.(\w+)', s))):
        if ref in ('root',): continue
        if ref not in ids:
            print(f"   FAIL {f}: {var.group(1)}.{ref} not in {layout}"); fails.append(f'bind {f}.{ref}')
ok_if('bind')

# ---- cross-file references ------------------------------------------------
section("cross-file references")
decl = {}
for f, s in files.items():
    x  = set(re.findall(r'(?:const )?va[lr] ([A-Za-z_]\w*)\s*[=:]', s))
    x |= set(re.findall(r'fun (\w+)', s))
    x |= set(re.findall(r'^\s*(?:(?:data|enum|sealed|inner|private|internal) )*class (\w+)', s, re.M))
    x |= set(re.findall(r'^\s*(?:private |internal )?(?:object|interface) (\w+)', s, re.M))
    x |= set(re.findall(r'(\w+)\s*:\s*[\w<>?, ]+\s*[=,)]', s))
    decl[f[:-3]] = x
for f, s in files.items():
    for obj in decl:
        if obj == f[:-3]: continue
        for ref in sorted(set(re.findall(rf'\b{obj}\.(\w+)', strip_comments(s)))):
            if ref not in decl[obj]:
                print(f"   FAIL {f}: {obj}.{ref}"); fails.append(f'ref {obj}.{ref}')
ok_if('ref')

# ---- imports for generics -------------------------------------------------
section("findViewById generics imported")
builtin = {'View'}
for f, s in files.items():
    imp = {i.split('.')[-1] for i in re.findall(r'^import ([\w.]+)', s, re.M)}
    for t in sorted(set(re.findall(r'findViewById<(\w+)>', s))):
        if t not in imp and t not in builtin and '.' not in t:
            print(f"   FAIL {f}: {t} not imported"); fails.append(f'import {f}.{t}')
ok_if('import')

# ---- manifest -------------------------------------------------------------
section("every activity registered")
manifest = open('app/src/main/AndroidManifest.xml').read()
for f, s in files.items():
    if re.search(r'class \w+\s*:\s*AppCompatActivity', s):
        name = f[:-3]
        if f'.{name}"' not in manifest:
            print(f"   FAIL {name} is not in AndroidManifest.xml"); fails.append(f'manifest {name}')
ok_if('manifest')

print()
print("PASS" if not fails else f"{len(fails)} PROBLEM(S)")
sys.exit(1 if fails else 0)
