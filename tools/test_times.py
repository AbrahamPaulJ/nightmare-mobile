"""test_times.py [N] -- where the last unit-test run spent its time (from Gradle's XML reports).

Prints the total, the slowest N test cases, and how many sat near the 5 s FastIdle cap: a cluster of
those is a hang (state leaking between tests), not slow tests. Budget and rules: notes/HANDOFF.md §3c.
"""
import glob
import os
import re
import sys

N = int(sys.argv[1]) if len(sys.argv) > 1 else 10
here = os.path.dirname(os.path.abspath(__file__))
reports = glob.glob(os.path.join(here, "..", "app", "build", "test-results", "*", "TEST-*.xml"))
cases = []
for path in reports:
    text = open(path, encoding="utf-8").read()
    for m in re.finditer(r'<testcase name="([^"]+)" classname="([^"]+)" time="([\d.]+)"(.*?)(?:/>|</testcase>)', text, re.S):
        cases.append((float(m[3]), m[2].rsplit(".", 1)[-1] + "." + m[1], "<failure" in m[4]))
if not cases:
    sys.exit("no reports under app/build/test-results: run the suite first")
total = sum(c[0] for c in cases)
capped = [c for c in cases if 4.5 <= c[0] <= 6.0 and c[2]]
print(f"{len(cases)} tests, {total / 60:.1f} min of test time, {sum(c[2] for c in cases)} failed")
if capped:
    print(f"WARNING: {len(capped)} failures at the 5 s idle cap: a leak between tests, see HANDOFF §3c")
for t, name, failed in sorted(cases, reverse=True)[:N]:
    print(f"  {t:7.2f} s  {name}{'  FAILED' if failed else ''}")
