#!/usr/bin/env python3
"""Publishes a per-suite summary of JUnit XML results as ONE check-run notice.

Usage: test_summary.py TITLE GLOB [GLOB ...]
The annotation is readable through the GitHub API, so a reviewer can confirm which suites
actually ran (and that none were skipped) without access to the raw job logs.
"""
import glob
import sys
import xml.etree.ElementTree as ET

title, patterns = sys.argv[1], sys.argv[2:]
rows = {}
for pattern in patterns:
    for path in glob.glob(pattern, recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for s in suites:
            name = (s.get("name") or path).split(".")[-1]
            t, f, e, sk = (int(float(s.get(k, 0) or 0)) for k in ("tests", "failures", "errors", "skipped"))
            r = rows.setdefault(name, [0, 0, 0])
            r[0] += t
            r[1] += f + e
            r[2] += sk

if not rows:
    print(f"::warning title={title}::no JUnit results found for {patterns}")
    sys.exit(0)

total = [sum(r[i] for r in rows.values()) for i in range(3)]
lines = [f"total tests={total[0]} failed={total[1]} skipped={total[2]}"]
lines += [f"{n}: {r[0]} run, {r[1]} failed, {r[2]} skipped" for n, r in sorted(rows.items())]
msg = "%0A".join(lines)[:60000]
print(f"::notice title={title}::{msg}")
