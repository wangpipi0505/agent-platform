from pathlib import Path
import hashlib
import json
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent
JAVA = Path("C:/Program Files/Eclipse Adoptium/jdk-21.0.12.8-hotspot/bin")
report = ROOT / "docs/evidence/iam-stage-2/full-surefire/TEST-cn.agentplatform.common.authority.TrustedJwtDecoderTest.xml"
cp = next(p.attrib["value"] for p in ET.parse(report).findall("./properties/property") if p.attrib["name"] == "java.class.path")
classes = OUT / "classes"
classes.mkdir(exist_ok=True)

def verify():
    counts = {}
    for name, base in [("source-after.json", ROOT), ("target-sha256.json", ROOT / "admin-common/target"),
                       ("manifest.json", ROOT)]:
        entries = json.loads((ROOT / "docs/evidence/iam-stage-2" / name).read_text(encoding="utf-8"))
        bad = [p for p, sha in entries.items() if hashlib.sha256((base / p).read_bytes()).hexdigest() != sha]
        if bad:
            raise RuntimeError(f"{name}: changed entries {bad}")
        counts[name] = len(entries)
    return counts

before = verify()
commands = [
    [str(JAVA / "javac.exe"), "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), str(OUT / "Stage2ReviewProbe.java")],
    [str(JAVA / "java.exe"), "-Djdk.net.unixdomain.tmpdir=V:/.local/tmp", "-cp", str(classes) + ";" + cp, "cn.agentplatform.common.authority.Stage2ReviewProbe"],
]
with (OUT / "probe-run2.log").open("x", encoding="utf-8") as log:
    log.write("Independent finite probe; original source/target/manifest verified before execution.\n")
    log.flush()
    for command in commands:
        result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=45)
        log.write(f"\nEXIT {result.returncode}\n")
        log.flush()
        if result.returncode:
            raise RuntimeError(f"probe command exited {result.returncode}")
after = verify()
(OUT / "preservation.json").write_text(json.dumps({"before": before, "after": after, "equal": before == after}, indent=2), encoding="utf-8")
print((OUT / "probe-run2.log").read_text(encoding="utf-8"))
print(json.dumps({"before": before, "after": after, "equal": before == after}))
