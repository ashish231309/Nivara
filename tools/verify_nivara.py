#!/usr/bin/env python3
"""Static verification of the Nivara Android project (no JDK/Android SDK required).

Covers the checks a compiler would normally make that are still checkable by inspection:
XML/TOML/YAML validity, package/path agreement, resource references, unused resources,
catalog usage agreement, brace balance, and the security baseline.
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
errors: list[str] = []
warnings: list[str] = []
notes: list[str] = []


def err(msg: str) -> None:
    errors.append(msg)


def warn(msg: str) -> None:
    warnings.append(msg)


def strip_comments(text: str) -> str:
    """Removes block and line comments, keeping string literals intact.

    Static checks that look for dangerous *code* must not fire on documentation that discusses
    the very thing being checked (for example a comment explaining that ECB is not used).
    """
    without_blocks = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", without_blocks)


def attr(el, name: str, default=None):
    """Read an Android-namespaced attribute regardless of namespace expansion."""
    local = name.split(":")[-1]
    for key, value in el.attrib.items():
        if key == name or key.split("}")[-1] == local:
            return value
    return default


# ---------------------------------------------------------------- XML well-formedness
xml_files = sorted(ROOT.rglob("*.xml"))
for path in xml_files:
    try:
        ET.parse(path)
    except ET.ParseError as e:
        err(f"malformed XML: {path.relative_to(ROOT)}: {e}")
notes.append(f"{len(xml_files)} XML files parsed")

# ---------------------------------------------------------------- version catalog
import tomllib

try:
    catalog = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())
except Exception as e:
    err(f"version catalog not parseable: {e}")
    catalog = {}

libs_declared = set(catalog.get("libraries", {}))
plugins_declared = set(catalog.get("plugins", {}))
versions_declared = set(catalog.get("versions", {}))

for name, body in catalog.get("libraries", {}).items():
    version = body.get("version")
    if isinstance(version, dict) and version.get("ref") not in versions_declared:
        err(f"catalog library '{name}' references unknown version '{version.get('ref')}'")
    if "group" not in body or "name" not in body:
        err(f"catalog library '{name}' is missing group or name")
for name, body in catalog.get("plugins", {}).items():
    version = body.get("version")
    if isinstance(version, dict) and version.get("ref") not in versions_declared:
        err(f"catalog plugin '{name}' references unknown version '{version.get('ref')}'")
notes.append(f"catalog: {len(libs_declared)} libraries, {len(plugins_declared)} plugins, {len(versions_declared)} versions")

# ---------------------------------------------------------------- gradle usage
gradle_files = [ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts", ROOT / "app/build.gradle.kts"]
gradle_text = "\n".join(p.read_text() for p in gradle_files)

used_libs = {
    m.group(1).replace(".", "-")
    for m in re.finditer(r"libs\.(?!plugins\.)((?:[a-z0-9]+\.)*[a-z0-9]+)", gradle_text)
}
used_plugins = {m.group(1).replace(".", "-") for m in re.finditer(r"alias\(libs\.plugins\.((?:[a-z0-9]+\.)*[a-z0-9]+)\)", gradle_text)}

for used in sorted(used_libs):
    if used not in libs_declared:
        err(f"build script uses libs.{used}: not declared in the version catalog")
for used in sorted(used_plugins):
    if used not in plugins_declared:
        err(f"build script aliases plugin '{used}': not declared in the version catalog")
for name in sorted(libs_declared):
    if name not in used_libs:
        warn(f"version catalog entry '{name}' is declared but never used in a build script")

# ---------------------------------------------------------------- YAML workflows
try:
    import yaml

    for path in sorted((ROOT / ".github/workflows").glob("*.yml")):
        data = yaml.safe_load(path.read_text())
        if "jobs" not in (data or {}):
            err(f"{path.name}: workflow has no jobs")
        if "on" not in (data or {}) and True not in (data or {}):
            err(f"{path.name}: workflow has no trigger")
        for job_name, job in (data.get("jobs") or {}).items():
            if "steps" not in job:
                err(f"{path.name}: job '{job_name}' has no steps")
            for step in job.get("steps", []):
                if "uses" in step and "run" in step:
                    err(f"{path.name}: step '{step.get('name')}' uses both 'uses' and 'run'")
        notes.append(f"workflow {path.name}: {len(data.get('jobs', {}))} job(s) parsed")
except ImportError:
    warn("PyYAML unavailable: workflow YAML not parsed")

# ---------------------------------------------------------------- kotlin sources
kt_files = sorted(ROOT.rglob("*.kt"))
main_kt = [p for p in kt_files if "/src/main/" in str(p)]
test_kt = [p for p in kt_files if "/src/test/" in str(p)]
androidtest_kt = [p for p in kt_files if "/src/androidTest/" in str(p)]

declared_symbols: set[str] = set()

for path in kt_files:
    rel = path.relative_to(ROOT)
    text = path.read_text()
    m = re.search(r"^package\s+([\w.]+)\s*$", text, re.MULTILINE)
    if not m:
        err(f"{rel}: missing or malformed package declaration")
        continue
    package = m.group(1)
    if not str(path.parent).endswith(package.replace(".", "/")):
        err(f"{rel}: package '{package}' does not match its directory")

    stripped = re.sub(r'"""(.|\n)*?"""', '""', text)
    stripped = re.sub(r'"(?:[^"\\\n]|\\.)*"', '""', stripped)
    stripped = re.sub(r"//[^\n]*", "", stripped)
    stripped = re.sub(r"/\*.*?\*/", "", stripped, flags=re.S)
    for open_ch, close_ch in (("{", "}"), ("(", ")"), ("[", "]")):
        if stripped.count(open_ch) != stripped.count(close_ch):
            err(f"{rel}: unbalanced '{open_ch}{close_ch}' "
                f"({stripped.count(open_ch)} opening vs {stripped.count(close_ch)} closing)")

    for m2 in re.finditer(r"^(?:internal |private |public )*(?:sealed |data |enum |abstract |open |annotation )*"
                          r"(?:class|interface|object)\s+([A-Za-z_]\w*)", text, re.MULTILINE):
        declared_symbols.add(m2.group(1))
    for m2 in re.finditer(r"^(?:internal |private )?(?:inline |suspend )?fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,*\s]*?\.)?([A-Za-z_]\w*)\s*\(", text, re.MULTILINE):
        declared_symbols.add(m2.group(1))

for path in kt_files:
    rel = path.relative_to(ROOT)
    text = path.read_text()
    for m in re.finditer(r"^import\s+(com\.nivara\.app\.[\w.]+)", text, re.MULTILINE):
        symbol = m.group(1).split(".")[-1]
        if symbol in {"R", "BuildConfig"}:
            continue  # generated by the Android Gradle Plugin
        if symbol not in declared_symbols:
            err(f"{rel}: import '{m.group(1)}' does not resolve to a symbol declared in this project")

notes.append(f"{len(kt_files)} Kotlin files (main {len(main_kt)}, test {len(test_kt)}, androidTest {len(androidtest_kt)}), "
             f"{len(declared_symbols)} declared symbols")

# ---------------------------------------------------------------- string resources
strings_root = ET.parse(ROOT / "app/src/main/res/values/strings.xml").getroot()
string_names = {attr(el, "name") for el in strings_root.findall("string")}

referenced_strings = set()
for path in kt_files:
    referenced_strings |= {m.group(1) for m in re.finditer(r"R\.string\.(\w+)", path.read_text())}

for name in sorted(referenced_strings - string_names):
    err(f"code references R.string.{name}, not defined in strings.xml")
for name in sorted(string_names - referenced_strings):
    warn(f"string resource '{name}' is defined but never referenced")
notes.append(f"{len(string_names)} string resources, {len(referenced_strings)} referenced from code")

# ---------------------------------------------------------------- manifest + resources
manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
res_dir = ROOT / "app/src/main/res"


def resource_exists(kind: str, name: str) -> bool:
    for folder in res_dir.glob(f"{kind}*"):
        if folder.is_dir():
            for f in folder.iterdir():
                if f.stem == name:
                    return True
    for values in res_dir.glob("values*"):
        for f in values.glob("*.xml"):
            try:
                root = ET.parse(f).getroot()
            except ET.ParseError:
                continue
            for child in root:
                if attr(child, "name") == name:
                    return True
    return False


for m in re.finditer(r'@(drawable|mipmap|color|style|xml|string)/([\w.]+)', manifest):
    kind, name = m.group(1), m.group(2)
    if kind == "string":
        if name not in string_names:
            err(f"manifest references missing string '{name}'")
    elif not resource_exists(kind, name):
        err(f"manifest references missing resource @{kind}/{name}")

if "<uses-permission" in manifest:
    err("manifest declares permissions; the foundation release must request none")
if 'android:usesCleartextTraffic="false"' not in manifest:
    err("manifest does not disable cleartext traffic")
if 'android:allowBackup="false"' not in manifest:
    err("manifest does not disable backup")
if "android:debuggable" in manifest:
    err("manifest sets android:debuggable explicitly")
if not (res_dir / "xml" / "data_extraction_rules.xml").exists():
    err("res/xml/data_extraction_rules.xml (referenced by the manifest) is missing")

for style_file in sorted(res_dir.glob("values*/themes.xml")):
    root = ET.parse(style_file).getroot()
    for style in root.findall("style"):
        for item in style.findall("item"):
            value = (item.text or "").strip()
            if not value.startswith("@") or value.startswith("@android:"):
                continue
            kind, _, name = value[1:].partition("/")
            if kind in {"color", "drawable", "style"} and not resource_exists(kind, name):
                err(f"{style_file.parent.name}/{style_file.name}: '{style.attrib['name']}' item "
                    f"'{attr(item, 'name')}' references missing resource {value}")

# every R.<type>.<name> reference from code must exist as a resource
for path in kt_files:
    rel = path.relative_to(ROOT)
    for m in re.finditer(r"R\.(drawable|mipmap|color|style|xml|plurals|array|font|raw|anim)\.(\w+)", path.read_text()):
        kind, name = m.group(1), m.group(2)
        if not resource_exists(kind, name):
            err(f"{rel}: references missing resource R.{kind}.{name}")

# ---------------------------------------------------------------- vector icons
for path in sorted((res_dir / "drawable").glob("*.xml")):
    root = ET.parse(path).getroot()
    if root.tag != "vector":
        continue
    viewport = float(attr(root, "android:viewportWidth", 0) or 0)
    for group in root.findall("group"):
        scale = float(attr(group, "android:scaleX", 1))
        translate = float(attr(group, "android:translateX", 0))
        glyph = 24.0 * scale
        if translate < 0 or translate + glyph > viewport:
            err(f"{path.name}: glyph {translate}..{translate + glyph} exceeds the {viewport} viewport")

# ---------------------------------------------------------------- security greps
secret_patterns = [
    r"(?i)(api[_-]?key|client[_-]?secret|passwd|password|private[_-]?key)\s*[:=]\s*[\"'][^\"']{4,}[\"']",
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"AKIA[0-9A-Z]{16}",
]
high_confidence_secret_patterns = [
    r"-----BEGIN [A-Z ]*PRIVATE KEY-----",
    r"AKIA[0-9A-Z]{16}",
    r"(?i)\bAWS_SECRET_ACCESS_KEY\s*[:=]",
]
scan_files = [p for p in ROOT.rglob("*")
              if p.suffix in {".kt", ".kts", ".xml", ".toml", ".properties", ".yml", ".md"} and ".git" not in p.parts]
for path in scan_files:
    is_test = "/src/test/" in str(path) or "/src/androidTest/" in str(path)
    text = path.read_text(errors="ignore")
    for pattern in high_confidence_secret_patterns:
        if re.search(pattern, text):
            err(f"{path.relative_to(ROOT)}: possible hard-coded secret")
    if is_test:
        continue
    for pattern in secret_patterns:
        if re.search(pattern, text):
            err(f"{path.relative_to(ROOT)}: possible hard-coded secret")

for path in main_kt:
    text = path.read_text()
    if re.search(r"\bLog\.[vdiew]\s*\(", text):
        err(f"{path.relative_to(ROOT)}: platform logging call in application code")
    if re.search(r"(?<!\w)println\s*\(", text):
        err(f"{path.relative_to(ROOT)}: println call in application code")

# ---------------------------------------------------------------- cryptographic hygiene
security_sources = sorted((ROOT / "app/src/main/java/com/nivara/app/domain/security").glob("*.kt")) + \
    sorted((ROOT / "app/src/main/java/com/nivara/app/data/security").glob("*.kt"))
main_all_kt = sorted((ROOT / "app/src/main").rglob("*.kt"))

for path in security_sources:
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())

    # no predictable randomness, ever
    for pattern in (r"\bjava\.util\.Random\b", r"\bkotlin\.random\.Random\b", r"\bMath\.random\b",
                    r"\bkotlin\.random\.\w+\b"):
        if re.search(pattern, text):
            err(f"{rel}: predictable randomness ({pattern})")

    # no time-based or identity-based values in security code
    for pattern in (r"System\.currentTimeMillis", r"System\.nanoTime", r"System\.identityHashCode"):
        if re.search(pattern, text):
            err(f"{rel}: non-cryptographic value ({pattern}) used in security code")

    # credentials must never become immutable Strings
    for pattern in (r"String\s*\(\s*password", r"password\.concatToString", r"password\.joinToString",
                    r"String\s*\(\s*credential", r"\bcharArrayOf\([^)]*\)\.concatToString"):
        if re.search(pattern, text):
            err(f"{rel}: credential converted to an immutable String ({pattern})")

    # no unauthenticated or weak cipher modes
    if re.search(r'"[^"]*ECB[^"]*"|BLOCK_MODE_ECB', text):
        err(f"{rel}: ECB mode referenced")
    for match in re.finditer(r'"(AES/[^"\s]+)"', text):
        if "GCM" not in match.group(1):
            err(f"{rel}: non-GCM AES transformation '{match.group(1)}'")

    # secrets must not reach logs or crash output
    for pattern in (r"\bLog\.[vdiew]\s*\(", r"\bprintln\s*\(", r"printStackTrace\s*\("):
        if re.search(pattern, text):
            err(f"{rel}: diagnostic output that could leak material ({pattern})")

    # no key material in the source itself
    for match in re.finditer(r'"([0-9a-fA-F]{32,})"', text):
        err(f"{rel}: long hexadecimal literal in security code ({match.group(1)[:16]}...)")

    if "package com.nivara.app" not in text:
        err(f"{rel}: unexpected package")

    # security code may not import a UI or Android framework type outside the data layer
    if "data/security" in str(path):
        for match in re.finditer(r"^import\s+(androidx\.\w+|android\.widget\.\w+)", text, re.MULTILINE):
            err(f"{rel}: platform UI import in security code ({match.group(1)})")

# Byte arrays have no string helpers in the Kotlin standard library. A parser that assumes
# otherwise does not compile, so it is worth catching here as well as in the compiler.
string_only_helpers = ("startsWith", "endsWith", "substring", "split", "trim", "replace")
for path in security_sources:
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for helper in string_only_helpers:
        for match in re.finditer(rf"\b(\w+)\.{helper}\s*\(", text):
            target = match.group(1)
            # only flag identifier-shaped receivers; arrays and strings are indistinguishable
            # statically, so this looks for the byte-array style names used in this package
            if re.search(rf"\bval\s+{target}\s*(?::\s*ByteArray)?\s*=", text) or target in {
                "bytes", "envelope", "container", "nonce", "material", "buffer", "wrappedKey",
                "ciphertext", "plaintext", "magic",
            }:
                err(f"{rel}: '{helper}' is not available on a byte array (receiver '{target}')")

# the domain security layer must stay free of Android and JCE implementation types
for path in sorted((ROOT / "app/src/main/java/com/nivara/app/domain/security").glob("*.kt")):
    rel = path.relative_to(ROOT)
    allowed_platform_apis = ("java.security.MessageDigest", "java.security.SecureRandom")
    for match in re.finditer(r"^import\s+(android\.\w+|androidx\.\w+|javax\.crypto\.\w+|java\.security\.\w+)",
                             path.read_text(), re.MULTILINE):
        if not match.group(1).startswith(allowed_platform_apis):
            err(f"{rel}: domain layer imports a platform implementation type ({match.group(1)})")

# every domain security contract must be implemented and wired in the composition root
container = (ROOT / "app/src/main/java/com/nivara/app/di/AppContainer.kt").read_text()
contracts = sorted(p.stem for p in (ROOT / "app/src/main/java/com/nivara/app/domain/security").glob("*.kt")
                   if p.stem.endswith(("Service", "Provider", "Store", "Wrapper")))
for contract in contracts:
    if contract not in container:
        err(f"AppContainer does not expose the '{contract}' contract")
    implemented = [p for p in (ROOT / "app/src/main/java/com/nivara/app/data/security").glob("*.kt")
                   if re.search(rf":\s*{contract}\b|,\s*{contract}\b", p.read_text())]
    if not implemented:
        err(f"no data-layer implementation found for '{contract}'")
notes.append(f"security review: {len(security_sources)} security sources, {len(contracts)} contracts wired")

# documentation that the code refers to must exist
for doc in ("docs/crypto/envelope-format.md", "docs/crypto/README.md", "tools/crypto_reference.py"):
    if not (ROOT / doc).exists():
        err(f"documentation or tooling referenced by the code is missing: {doc}")

# An integer literal compared against a byte expression never matches in JUnit: the assertion
# boxes the literal as Integer and the byte as Byte, so the test fails (or, worse, a negated form
# passes). Warn about it in test sources.
for path in sorted(ROOT.rglob("*.kt")):
    if "/src/test/" not in str(path) and "/src/androidTest/" not in str(path):
        continue
    pattern = r"assert(?:Not)?Equals\(\s*-?\d+\s*,\s*[\w.]+(?:\(\))?\[[^\]]*\]\s*[,)]"
    for match in re.finditer(pattern, path.read_text()):
        warn(f"{path.relative_to(ROOT)}: integer literal compared with a byte expression "
             f"({match.group(0).strip()}) - box the literal with .toByte()")

# ---------------------------------------------------------------- wrapper / hygiene
wrapper_props = (ROOT / "gradle/wrapper/gradle-wrapper.properties").read_text()
if "distributionUrl" not in wrapper_props:
    err("gradle-wrapper.properties has no distributionUrl")
wrapper_jar = ROOT / "gradle/wrapper/gradle-wrapper.jar"
if not wrapper_jar.exists() or wrapper_jar.stat().st_size < 20000:
    err("gradle-wrapper.jar is missing or truncated")
if not os.access(ROOT / "gradlew", os.X_OK):
    err("gradlew is not executable")

for required in ("README.md", "LICENSE", ".gitignore", "gradle.properties", "settings.gradle.kts",
                 "build.gradle.kts", "app/build.gradle.kts", "app/proguard-rules.pro",
                 ".github/workflows/android-ci.yml", "gradle/libs.versions.toml"):
    if not (ROOT / required).exists():
        err(f"required file missing: {required}")

if not re.search(r"Copyright \(c\)(?: 20\d\d)? Ashish Kumar", (ROOT / "LICENSE").read_text()):
    err("LICENSE does not carry the expected copyright line")

# ---------------------------------------------------------------- report
print("=== notes ===")
for note in notes:
    print(f"  - {note}")
print("\n=== warnings ===")
print("\n".join(f"  WARN  {w}" for w in warnings) or "  none")
print("\n=== errors ===")
print("\n".join(f"  ERROR {e}" for e in errors) or "  none")
print(f"\nRESULT: {'FAIL' if errors else 'PASS'} ({len(errors)} errors, {len(warnings)} warnings)")
sys.exit(1 if errors else 0)
