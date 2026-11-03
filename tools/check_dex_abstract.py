#!/usr/bin/env python3
"""Every concrete class in an APK implements every method of its interfaces: the check ART's verifier skips.

Usage: python -I tools/check_dex_abstract.py <apk>   (needs the SDK's build-tools dexdump)

⚠⚠ Why: 1.6.272 shipped `AbstractMethodError ... v1.Pc.j(String, long, float[], int, int, int)` on every
safetensors to GGUF conversion (the Anima download, MOSS and Z-Image imports; GitHub #77, #86). R8 merged the
anonymous `SafetensorsGguf.Rule` objects of AnimaGguf and MossGguf into one class and gave it every interface
method but `decoded`, whose default body is empty. `tools/verify_dex.sh` passed it (a missing method fails only
when called) and a dev build has no R8. This script finds 1 such class in 1.6.272's APK.
⭐ Exit 0 = none. Classes outside the dex (the framework, the JDK) count as providing what they might.
"""
import os
import re
import subprocess
import sys
import tempfile
import zipfile


OBJECT_METHODS = {"equals(Ljava/lang/Object;)Z", "hashCode()I", "toString()Ljava/lang/String;"}


def dexdump() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    tools = os.path.join(sdk, "build-tools")
    for v in sorted(os.listdir(tools), reverse=True):
        for name in ("dexdump.exe", "dexdump"):
            p = os.path.join(tools, v, name)
            if os.path.exists(p):
                return p
    sys.exit("no dexdump under " + tools)


def parse(text: str, classes: dict) -> None:
    cur = None
    section = None
    for line in text.splitlines():
        m = re.match(r"  Class descriptor  : '(.+)'", line)
        if m:
            cur = {"super": None, "ifaces": [], "methods": {}, "abstract": False, "interface": False}
            classes[m.group(1)] = cur
            section = None
            continue
        if cur is None:
            continue
        if line.startswith("  Access flags"):
            cur["abstract"] = "ABSTRACT" in line
            cur["interface"] = "INTERFACE" in line
        elif line.startswith("  Superclass"):
            cur["super"] = re.search(r"'(.+)'", line).group(1)
        elif line.startswith("  Interfaces"):
            section = "ifaces"
        elif line.startswith("  Direct methods") or line.startswith("  Virtual methods"):
            section = "methods"
        elif line.startswith("  Static fields") or line.startswith("  Instance fields"):
            section = None
        elif section == "ifaces":
            m = re.match(r"    #\d+\s+: '(.+)'", line)
            if m:
                cur["ifaces"].append(m.group(1))
        elif section == "methods":
            m = re.match(r"      name\s+: '(.+)'", line)
            if m:
                pending = m.group(1)
                cur["_name"] = pending
                continue
            m = re.match(r"      type\s+: '(.+)'", line)
            if m and "_name" in cur:
                cur["_sig"] = cur.pop("_name") + m.group(1)
                continue
            m = re.match(r"      access\s+: 0x[0-9a-f]+ \((.*)\)", line)
            if m and "_sig" in cur:
                flags = m.group(1)
                cur["methods"][cur.pop("_sig")] = "ABSTRACT" in flags, "STATIC" in flags


def main() -> int:
    apk = sys.argv[1]
    dd = dexdump()
    classes: dict = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if re.fullmatch(r"classes\d*\.dex", n):
                path = z.extract(n, tmp)
                out = subprocess.run([dd, path], capture_output=True, text=True, encoding="utf-8", errors="replace")
                parse(out.stdout, classes)

    def all_ifaces(c, seen):
        k = classes.get(c)
        if not k:
            return
        for i in k["ifaces"]:
            if i not in seen:
                seen.add(i)
                all_ifaces(i, seen)
        if k["super"]:
            all_ifaces(k["super"], seen)

    def chain_has(c, sig):
        """True when [c] or a superclass has a concrete [sig], or the chain leaves the dex."""
        while c:
            k = classes.get(c)
            if c == "Ljava/lang/Object;":
                # ⚠ Not in the dex, but it provides only its own methods: counting it as "might" passed 1.6.272.
                return sig in OBJECT_METHODS
            if not k:
                return True
            m = k["methods"].get(sig)
            if m and not m[0] and not m[1]:
                return True
            c = k["super"]
        return False

    missing = []
    for name, k in classes.items():
        if k["abstract"] or k["interface"]:
            continue
        ifaces = set()
        all_ifaces(name, ifaces)
        if any(i not in classes for i in ifaces):
            ifaces = {i for i in ifaces if i in classes}
        for i in ifaces:
            for sig, (abstract, static) in classes[i]["methods"].items():
                if not abstract or static:
                    continue
                if chain_has(name, sig):
                    continue
                if any(sig in classes[j]["methods"] and not classes[j]["methods"][sig][0] for j in ifaces if j in classes):
                    continue  # a default body in another interface
                missing.append(f"{name} lacks {i}->{sig}")
    for m in missing:
        print(m)
    print(f"{len(classes)} classes; " + (f"FAIL: {len(missing)} missing interface method(s)" if missing else "OK: none missing"))
    return 1 if missing else 0


if __name__ == "__main__":
    sys.exit(main())
