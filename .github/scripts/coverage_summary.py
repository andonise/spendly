"""Prints a Markdown coverage table for the GitHub Actions run summary.

Usage: coverage_summary.py <app> <report>
Reads a JaCoCo XML report (core), a Vitest json-summary (web) or a Cobertura XML report (pdf-parser).
"""
import json
import os
import sys
import xml.etree.ElementTree as ET

GATE = "Gate: 80% of lines."


def jacoco(path):
    counters = {c.get("type"): c for c in ET.parse(path).getroot().findall("counter")}
    for name in ("LINE", "BRANCH", "INSTRUCTION", "METHOD"):
        missed, covered = int(counters[name].get("missed")), int(counters[name].get("covered"))
        yield name.lower(), 100 * covered / (missed + covered)


def vitest(path):
    total = json.load(open(path, encoding="utf-8"))["total"]
    for name in ("lines", "branches", "statements", "functions"):
        yield name, total[name]["pct"]


def cobertura(path):
    root = ET.parse(path).getroot()
    yield "lines", 100 * float(root.get("line-rate"))


def main():
    app, path = sys.argv[1], sys.argv[2]
    print(f"### {app} coverage\n")
    if not os.path.exists(path):
        print("No coverage report: the build failed before the tests ran.")
        return
    if path.endswith(".json"):
        rows = vitest(path)
    elif ET.parse(path).getroot().tag == "report":
        rows = jacoco(path)
    else:
        rows = cobertura(path)
    print("| Counter | Covered |\n|---|---|")
    for name, pct in rows:
        print(f"| {name} | {pct:.1f}% |")
    print(f"\n{GATE}")


if __name__ == "__main__":
    main()
