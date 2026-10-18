#!/usr/bin/env python3
"""Turn Gradle/JUnit failures into GitHub annotations (visible without downloading logs)."""
import glob
import sys
import xml.etree.ElementTree as ET


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "").replace("\n", "%0A")


def annotate(title: str, body: str) -> None:
    print(f"::error title={esc(title)}::{esc(body[:20000])}")


logs = sys.argv[1:]
for path in logs:
    try:
        text = open(path, encoding="utf-8", errors="replace").read()
    except FileNotFoundError:
        continue
    lines = text.splitlines()
    compile_errors = [l for l in lines if l.startswith("e: ")]
    if compile_errors:
        annotate(f"Kotlin compile errors ({path})", "\n".join(compile_errors[:80]))
    if path.endswith("server-run.log"):
        errs = [l for l in lines if any(k in l for k in ("Exception", "ERROR", "Caused by", "\tat com.taskly"))]
        if errs:
            annotate("Server runtime errors", "\n".join(errs[:120]))
    if "FAILURE:" in text:
        idx = text.index("FAILURE:")
        annotate(f"Gradle failure ({path})", text[idx:idx + 4000])

count = 0
for xml_path in glob.glob("**/build/test-results/**/*.xml", recursive=True):
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError:
        continue
    has_failures = any(c.find("failure") is not None or c.find("error") is not None for c in root.iter("testcase"))
    if has_failures:
        # Server-side stack traces end up in the suite's stdout/stderr, not in the assertion
        out = "\n".join((n.text or "") for n in root.iter() if n.tag in ("system-out", "system-err"))
        keep = [l for l in out.splitlines()
                if any(k in l for k in ("Exception", "ERROR", "Caused by", "SQL", "\tat com.taskly"))]
        if keep:
            annotate(f"Server log ({root.get('name')})", "\n".join(keep[:120]))
    for case in root.iter("testcase"):
        for tag in ("failure", "error"):
            node = case.find(tag)
            if node is None:
                continue
            count += 1
            if count > 30:
                continue
            msg = (node.get("message") or "") + "\n" + (node.text or "")[:3000]
            annotate(f"{case.get('classname')}.{case.get('name')}", msg)
if count > 30:
    annotate("More test failures", f"{count - 30} more failures not shown")
