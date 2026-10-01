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
                          r"(?:fun\s+)?(?:class|interface|object)\s+([A-Za-z_]\w*)", text, re.MULTILINE):
        declared_symbols.add(m2.group(1))
    for m2 in re.finditer(r"^(?:internal |private )?(?:inline |suspend )?fun\s+(?:<[^>]*>\s*)?(?:[\w.<>?,*\s]*?\.)?([A-Za-z_]\w*)\s*\(", text, re.MULTILINE):
        declared_symbols.add(m2.group(1))
    # Extension properties: `val NivaraResult<*>.isSuccess`. The receiver dot is required, so an
    # ordinary top-level `val name = …` is not mistaken for a type or a member.
    for m2 in re.finditer(r"^(?:internal |private |public )*(?:inline )?(?:val|var)\s+"
                          r"[\w.<>?,*\[\] ]+\.\s*([A-Za-z_]\w*)", text, re.MULTILINE):
        declared_symbols.add(m2.group(1))
    # Top-level properties and constants, which are imported by name like anything else. Only
    # column-zero declarations are collected, so a class member (indented) is never mistaken for a
    # package-level symbol that an import could legitimately resolve to.
    for m2 in re.finditer(r"^(?:internal |private |public )?(?:const |inline )?(?:val|var)\s+"
                          r"([A-Za-z_]\w*)\s*[:=]", text, re.MULTILINE):
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
jvm_test_methods = sum(len(re.findall(r"@Test\b", p.read_text())) for p in test_kt)
notes.append(f"{jvm_test_methods} JVM test methods; {len(androidtest_kt)} instrumented test files "
             f"(compiled, executed only when a device is attached)")

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

# Permissions are allowed only when a feature that exists requires them. Each entry is recorded
# here with its reason and must also be justified in docs/applock/README.md; the list is a ceiling,
# so adding a permission means changing this dictionary deliberately, in the change that needs it.
justified_permissions = {
    "android.permission.PACKAGE_USAGE_STATS":
        "App Lock: makes Nivara visible in Android's Usage Access list and lets the detection stage "
        "read usage statistics; granted by the user in Android's settings, never requested at runtime",
    "android.permission.SYSTEM_ALERT_WINDOW":
        "App Lock: lets the protection surface be drawn above the protected application; Android "
        "offers no other unprivileged way to do that, and the grant is given by the user in "
        "Android's own overlay settings",
}
applock_docs_path = ROOT / "docs/applock/README.md"
if not applock_docs_path.exists():
    err("docs/applock/README.md is missing: the App Lock platform decisions are not documented")
    applock_docs = ""
else:
    applock_docs = applock_docs_path.read_text()

declared_permissions = re.findall(r'<uses-permission[^>]*android:name="([^"]+)"', manifest)
for permission in sorted(set(declared_permissions)):
    if permission not in justified_permissions:
        err(f"manifest declares '{permission}', which is not on the justified allow-list")
    elif permission not in applock_docs:
        err(f"'{permission}' is declared but not justified in docs/applock/README.md")
notes.append(f"manifest: {len(declared_permissions)} permission(s), all on the justified allow-list")

# The deferred permission decisions are as important as the declared ones: overlay and battery
# exemptions are not part of the current feature set, and package visibility must stay narrow.
for deferred_permission, reason in (
    ("android.permission.QUERY_ALL_PACKAGES",
     "the launcher-intent <queries> element is the narrow mechanism for launcher discovery"),
    ("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
     "no battery exemption is justified by the current feature set"),
    ("android.permission.FOREGROUND_SERVICE",
     "detection runs as a plain started service; a foreground service must be justified by the "
     "feature it serves and documented first"),
    ("android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
     "no foreground service is declared, so no service type is declared either"),
    ("android.permission.POST_NOTIFICATIONS",
     "no foreground service and no user-visible notification yet; the notification permission "
     "belongs with the feature that shows one"),
    ("android.permission.BIND_ACCESSIBILITY_SERVICE",
     "detection reads usage events; an accessibility service is not used and would be a much "
     "broader capability"),
):
    if deferred_permission in manifest:
        err(f"manifest declares '{deferred_permission}': {reason}")

# Components that are not entry points are private: a service another application could start or
# stop would be a remote control for Nivara's background work.
manifest_root = ET.fromstring(manifest)
for service in manifest_root.iter("service"):
    if attr(service, "android:exported") != "false":
        err(f"manifest service '{attr(service, 'android:name')}' must declare "
            f"android:exported=\"false\"")

# Package visibility (API 30+): launcher applications are not visible by default, so discovery
# depends on exactly the intent signature the repository queries.
queries_element = manifest_root.find("queries")
if queries_element is None:
    err("manifest has no <queries> element: application discovery is invisible on API 30+")
else:
    query_actions = {attr(el, "name") for el in queries_element.iter("action")}
    query_categories = {attr(el, "name") for el in queries_element.iter("category")}
    if ("android.intent.action.MAIN" not in query_actions
            or "android.intent.category.LAUNCHER" not in query_categories):
        err("manifest <queries> does not declare the launcher intent (MAIN + LAUNCHER)")
    extra_query_packages = {attr(el, "name") for el in queries_element.iter("package")}
    if extra_query_packages:
        warn(f"manifest <queries> exposes whole packages: {sorted(extra_query_packages)}")

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

# Usage Access is not a runtime permission: it is granted in Android's settings, and Nivara calls
# no runtime permission request for any of its features. A genuine runtime permission added later
# must change this rule deliberately, in the same change.
for path in main_kt:
    text = strip_comments(path.read_text())
    if "requestPermissions(" in text:
        err(f"{path.relative_to(ROOT)}: runtime permission request; Usage Access is granted in "
            f"Android's settings and Nivara asks for nothing at runtime")

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
    sorted((ROOT / "app/src/main/java/com/nivara/app/data/security").glob("*.kt")) + \
    sorted((ROOT / "app/src/main/java/com/nivara/app/data/biometric").glob("*.kt")) + \
    sorted((ROOT / "app/src/main/java/com/nivara/app/data/session").glob("*.kt")) + \
    sorted((ROOT / "app/src/main/java/com/nivara/app/data/applock").glob("*.kt"))
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

# The App Lock domain packages stay as free of the platform as the security domain does: no
# Context, no PackageManager, no Intent, no UsageStatsManager, no Uri and no Settings. Those belong
# to the data layer that implements the contracts.
for domain_package in ("domain/app", "domain/permissions", "domain/applock", "domain/apphide",
                       "domain/launcher"):
    domain_dir = ROOT / f"app/src/main/java/com/nivara/app/{domain_package}"
    if not domain_dir.is_dir():
        err(f"{domain_package} is missing")
    for path in sorted(domain_dir.glob("*.kt")):
        rel = path.relative_to(ROOT)
        text = strip_comments(path.read_text())
        for match in re.finditer(r"\b(android|androidx|java\.io|java\.net)\.[\w.]+", text):
            err(f"{rel}: domain layer references a platform type ({match.group(0)})")

# Foreground detection reads usage events and nothing else. Aggregate usage statistics are a
# summary of the device's history, which App Lock does not need and must not collect, and the
# restricted activity-manager calls would be an attempt to work around the Usage Access grant.
for path in main_kt:
    text = strip_comments(path.read_text())
    for forbidden, reason in (
        ("queryUsageStats(", "App Lock reads usage events, never aggregate usage history"),
        ("getRunningTasks(", "the restricted activity-manager call is not a foreground source"),
        ("getRunningAppProcesses(", "the restricted activity-manager call is not a foreground source"),
        ("AccessibilityService", "foreground detection uses usage events, not accessibility"),
    ):
        if forbidden in text:
            err(f"{path.relative_to(ROOT)}: {forbidden} — {reason}")

# App Lock keeps one configuration file of package names, written atomically, and no other storage.
applock_data_dir = ROOT / "app/src/main/java/com/nivara/app/data/applock"
if not applock_data_dir.is_dir():
    err("data/applock is missing")
else:
    applock_sources = sorted(applock_data_dir.glob("*.kt"))
    if not applock_sources:
        err("data/applock holds no sources")
    for path in applock_sources:
        rel = path.relative_to(ROOT)
        text = strip_comments(path.read_text())
        for forbidden in ("SharedPreferences", "DataStore", "RoomDatabase", "openFileOutput",
                          "UsageStatsManager.queryUsageStats"):
            if forbidden in text:
                err(f"{rel}: App Lock storage must stay a single atomic file of package names "
                    f"({forbidden})")
        if ("File(" in text or "FileOutputStream" in text) and "AtomicFiles" not in text:
            err(f"{rel}: App Lock writes a file without the project's atomic write helper")

# App Lock must consume the existing session gate rather than grow one of its own: no second
# unlocked flag, no per-application session, no authentication cache.
for path in sorted(list(applock_data_dir.glob("*.kt")) +
                   list((ROOT / "app/src/main/java/com/nivara/app/domain/applock").glob("*.kt"))):
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for match in re.finditer(r"^(?:internal |private |public )*(?:sealed |data |enum |abstract |open )*"
                             r"(?:class|interface|object)\s+([A-Za-z_]\w*)", text, re.MULTILINE):
        name = match.group(1)
        if re.search(r"Session|Unlock|Authenticated", name):
            err(f"{rel}: App Lock declares '{name}'; the session and its unlocked state belong "
                f"to the existing SessionManager")
    if "currentState()" not in text and "SessionManager" in text:
        err(f"{rel}: App Lock reads the session without the gate's authoritative currentState()")

monitor_source = (applock_data_dir / "NivaraAppLockMonitor.kt")
if not monitor_source.exists():
    err("the App Lock monitor is missing")
else:
    monitor_text = strip_comments(monitor_source.read_text())
    if "dispatcher" in monitor_text and "Dispatchers.IO" not in monitor_text:
        warn("the App Lock monitor mentions a dispatcher without the IO dispatcher")

# The UI layer reads capabilities through the view models only. A composable that queried the
# package manager or the usage-stats services would put permission logic in the presentation layer.
for path in sorted((ROOT / "app/src/main/java/com/nivara/app/ui").rglob("*.kt")):
    text = strip_comments(path.read_text())
    for forbidden in ("PackageManager", "UsageStatsManager", "AppOpsManager", "checkOpNoThrow",
                      "Settings.ACTION"):
        if forbidden in text:
            err(f"{path.relative_to(ROOT)}: platform capability used from the UI layer ({forbidden})")

# App Lock screens list the device's applications, which Android treats as personal data, so each
# of them applies the project's single screenshot-protection implementation. The check is written
# over the screens rather than over one filename, so a screen added later has to opt in the same way.
setup_screen = ROOT / "app/src/main/java/com/nivara/app/ui/applock/AppLockSetupScreen.kt"
if not setup_screen.exists():
    err("the App Lock preparation screen is missing")
applock_screens = sorted((ROOT / "app/src/main/java/com/nivara/app/ui/applock").rglob("*Screen.kt"))
if not applock_screens:
    err("no App Lock screen was found")
for path in applock_screens:
    if "SecureScreenEffect()" not in path.read_text():
        err(f"{path.relative_to(ROOT)}: an App Lock screen must apply SecureScreenEffect()")

# The protected set has exactly one owner, and it is under data/applock. The App Lock UI reads and
# writes it through the repository contract, so nothing here may reach for the storage helper, the
# codec or a store of its own: a second store would be a second answer to "what is protected?".
for path in sorted((ROOT / "app/src/main/java/com/nivara/app/ui/applock").rglob("*.kt")):
    text = strip_comments(path.read_text())
    for forbidden in ("AtomicFiles", "ProtectedApplicationCodec", "FileProtectedApplicationRepository",
                      "SharedPreferences", "DataStore", "RoomDatabase", "openFileOutput",
                      "FileOutputStream"):
        if forbidden in text:
            err(f"{path.relative_to(ROOT)}: {forbidden} — the App Lock UI goes through the "
                f"protected-application repository, never storage of its own")
# Screenshot protection has exactly two sanctioned implementations, and no third: the composable
# effect that flags the activity window, and the window configuration of the App Lock protection
# surface, which is not an activity and therefore cannot use the effect.
sanctioned_flag_secure = {
    "app/src/main/java/com/nivara/app/ui/credential/SecureScreenEffect.kt",
    "app/src/main/java/com/nivara/app/ui/applock/overlay/WindowManagerOverlaySurface.kt",
}
flag_secure_files = {str(p.relative_to(ROOT)) for p in main_kt if "FLAG_SECURE" in p.read_text()}
for unexpected in sorted(flag_secure_files - sanctioned_flag_secure):
    err(f"{unexpected}: FLAG_SECURE is set here; screenshot protection belongs to "
        f"SecureScreenEffect or to the App Lock overlay window")
for missing in sorted(sanctioned_flag_secure - flag_secure_files):
    err(f"{missing}: the sanctioned screenshot protection is missing")

# The App Lock protection surface must actually be an overlay window, and there must be exactly one
# of them: a second implementation would be a second answer to "where is the surface drawn?".
overlay_window_files = [p for p in main_kt if "TYPE_APPLICATION_OVERLAY" in p.read_text()]
if len(overlay_window_files) != 1:
    err(f"{len(overlay_window_files)} files create an application-overlay window; the protection "
        f"surface must be the only one")
elif "FLAG_SECURE" not in overlay_window_files[0].read_text():
    err(f"{overlay_window_files[0].relative_to(ROOT)}: the protection window does not set FLAG_SECURE")

# The App Lock surface is presentation, not a second security layer. It must not verify anything
# itself: the credential layer verifies, the session gate decides, and the presentation layer only
# routes. Cryptography appearing in these packages would mean a verifier has been written here.
applock_presentation_dirs = [
    ROOT / "app/src/main/java/com/nivara/app/domain/applock",
    ROOT / "app/src/main/java/com/nivara/app/data/applock",
    ROOT / "app/src/main/java/com/nivara/app/ui/applock",
    ROOT / "app/src/main/java/com/nivara/app/data/permissions",
]
for directory in applock_presentation_dirs:
    for path in sorted(directory.rglob("*.kt")):
        rel = path.relative_to(ROOT)
        text = strip_comments(path.read_text())
        for forbidden, reason in (
            ("java.security", "App Lock must not do its own cryptography"),
            ("javax.crypto", "App Lock must not do its own cryptography"),
            ("KeyDerivationService", "key derivation belongs to the credential layer"),
            ("EncryptionService", "encryption belongs to the security layer"),
            ("MessageDigest", "App Lock must not hash anything itself"),
            ("Log.", "App Lock must not log: package names and credentials must never reach a log"),
            ("println(", "App Lock must not print: package names and credentials must never be logged"),
            ("HttpURLConnection", "App Lock transmits nothing"),
            ("okhttp", "App Lock transmits nothing"),
        ):
            if forbidden in text:
                err(f"{rel}: {forbidden} — {reason}")

# ---------------------------------------------------------------- hidden applications
# Hiding is recorded, never enforced. Nivara keeps a set of package names and nothing else, so the
# feature may not reach for any of the ways an application *could* take another one out of view: no
# component or enabled-state manipulation, no accessibility service, no device administrator, no
# second launcher, no cryptography and no logging. The rule is written over the whole feature rather
# than over one file, because the temptation to "actually hide" something would arrive as a helper.
hidden_dirs = [
    ROOT / "app/src/main/java/com/nivara/app/domain/apphide",
    ROOT / "app/src/main/java/com/nivara/app/data/apphide",
    ROOT / "app/src/main/java/com/nivara/app/ui/apphide",
]
for directory in hidden_dirs:
    if not directory.is_dir():
        err(f"{directory.relative_to(ROOT)} is missing")
for directory in hidden_dirs:
    for path in sorted(directory.rglob("*.kt")):
        rel = path.relative_to(ROOT)
        text = strip_comments(path.read_text())
        for forbidden, reason in (
            ("setApplicationEnabledSetting", "hiding never changes an application's enabled state"),
            ("setComponentEnabledSetting", "hiding never disables another application's components"),
            ("PackageManager", "hiding is recorded from the catalogue discovery already produced"),
            ("ApplicationInfo", "no platform application type belongs to the hidden set"),
            ("PackageInfo", "no platform application type belongs to the hidden set"),
            ("Drawable", "the hidden set is package names, never icons"),
            ("AccessibilityService", "hiding does not read the screen"),
            ("DevicePolicyManager", "hiding is not device administration"),
            ("CATEGORY_HOME", "the launcher is a later stage; hiding does not replace it"),
            ("java.security", "hidden state is not cryptography"),
            ("javax.crypto", "hidden state is not cryptography"),
            ("MessageDigest", "hidden state is not cryptography"),
            ("KeyDerivationService", "key derivation belongs to the credential layer"),
            ("EncryptionService", "encryption belongs to the security layer"),
            ("Log.", "hiding must not log: package names must never reach a log"),
            ("println(", "hiding must not print: package names must never be logged"),
            ("HttpURLConnection", "hiding transmits nothing"),
            ("okhttp", "hiding transmits nothing"),
        ):
            if forbidden in text:
                err(f"{rel}: {forbidden} — {reason}")

# Hiding consumes the existing session gate instead of growing one of its own: no hidden-app
# password, no second unlock flag, no authentication cache, no failure counter. If a file in the
# feature reads the session at all, it reads it through the gate's authoritative currentState().
for directory in hidden_dirs:
    for path in sorted(directory.rglob("*.kt")):
        rel = path.relative_to(ROOT)
        text = strip_comments(path.read_text())
        for match in re.finditer(
            r"^(?:internal |private |public )*(?:sealed |data |enum |abstract |open )*"
            r"(?:class|interface|object)\s+([A-Za-z_]\w*)",
            text,
            re.MULTILINE,
        ):
            name = match.group(1)
            if re.search(r"Session|Unlock|Password|Passcode|Biometric|Authenticat", name):
                err(f"{rel}: hiding declares '{name}'; the session and its unlocked state belong "
                    f"to the existing SessionManager")
        if "SessionManager" in text and "currentState()" not in text:
            err(f"{rel}: hiding reads the session without the gate's authoritative currentState()")

# Hiding and App Lock are separate dimensions. Nothing in the hiding feature may read, write or
# depend on the protected set, and all four combinations of the two are legitimate configuration.
for directory in hidden_dirs:
    for path in sorted(directory.rglob("*.kt")):
        rel = path.relative_to(ROOT)
        text = strip_comments(path.read_text())
        for forbidden in ("ProtectedApplication", "ProtectedApplicationCodec", "AppLockMonitor",
                          "ApplicationProtectionState", "AppLockProtectionRunner"):
            if forbidden in text:
                err(f"{rel}: {forbidden} — hiding must not depend on the protected set (Stage 7/8)")

# The stored hidden set has exactly one owner, and it is the repository under data/apphide. The UI
# reads and writes it through the contract, so nothing there may reach for the storage helper, the
# codec, the file name or a store of its own: a second store would be a second answer to "what is
# hidden?", and the two would disagree invisibly.
for path in sorted((ROOT / "app/src/main/java/com/nivara/app/ui/apphide").rglob("*.kt")):
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for forbidden in ("AtomicFiles", "HiddenApplicationCodec", "FileHiddenApplicationRepository",
                      "HIDDEN_APPLICATIONS_FILE", "APP_HIDE_DIRECTORY", "SharedPreferences",
                      "DataStore", "RoomDatabase", "openFileOutput", "FileOutputStream"):
        if forbidden in text:
            err(f"{rel}: {forbidden} — the hidden-application UI goes through the "
                f"hidden-application repository, never storage of its own")

hidden_writers = [p for p in main_kt if "AtomicFiles" in p.read_text() and "apphide" in str(p)]
if len(hidden_writers) != 1:
    err(f"{len(hidden_writers)} files under data/apphide write the hidden set; it must have "
        f"exactly one owner")
hidden_implementations = [
    p for p in (ROOT / "app/src/main/java/com/nivara/app/data").rglob("*.kt")
    if re.search(r":\s*HiddenApplicationRepository\b", p.read_text())
]
if len(hidden_implementations) != 1:
    err(f"{len(hidden_implementations)} implementations of HiddenApplicationRepository; the stored "
        f"hidden set must have exactly one owner")
for path in main_kt:
    text = strip_comments(path.read_text())
    for match in re.finditer(r"\b(?:class|interface|object)\s+([A-Za-z_]\w*)", text):
        name = match.group(1)
        if re.search(r"Hidden[A-Za-z]*(Store|Cache|Database|Preferences)$", name) or (
            "Hidden" in name and name.endswith(("SettingsRepository", "UiStore"))
        ):
            err(f"{path.relative_to(ROOT)}: '{name}' — hidden-application state belongs to the one "
                f"repository, not to a store, cache or settings copy")

# The screen that shows which applications are hidden is as sensitive as the App Lock settings
# screen, so it applies the project's single screenshot-protection implementation.
hidden_screens = sorted((ROOT / "app/src/main/java/com/nivara/app/ui/apphide").rglob("*Screen.kt"))
if not hidden_screens:
    err("no hidden-application screen was found")
for path in hidden_screens:
    if "SecureScreenEffect()" not in path.read_text():
        err(f"{path.relative_to(ROOT)}: a hidden-application screen must apply SecureScreenEffect()")

# The hidden set stores package names and nothing else: no labels, no icons, no timestamps, no UI
# state. Names, not application objects, are what a launcher needs and what a file may hold.
for path in sorted((ROOT / "app/src/main/java/com/nivara/app/data/apphide").glob("*.kt")) + sorted(
    (ROOT / "app/src/main/java/com/nivara/app/domain/apphide").glob("*.kt")
):
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for forbidden, reason in (
        ("Drawable", "the hidden set stores package names, never icons"),
        ("label", "the hidden set stores package names, never labels"),
        ("icon", "the hidden set stores package names, never icons"),
        ("System.currentTimeMillis", "the hidden set carries no timestamps"),
        ("Date(", "the hidden set carries no timestamps"),
    ):
        if forbidden in text:
            err(f"{rel}: {forbidden} — {reason}")

# ---------------------------------------------------------------- the launcher
# Nivara can be the device's Home application, and the Home contract is the one place the project
# needs an exported component. The rules below describe that surface exactly, from the manifest
# rather than from a filename, and then constrain what the launcher may do behind it.
launcher_sources = sorted((ROOT / "app/src/main/java/com/nivara/app/ui/launcher").rglob("*.kt"))
if not launcher_sources:
    err("no launcher sources were found under ui/launcher")

home_activities = []
for activity in manifest_root.iter("activity"):
    filters = list(activity.findall("intent-filter"))
    categories = {attr(category, "name") for filter_ in filters for category in filter_.iter("category")}
    actions = {attr(action, "name") for filter_ in filters for action in filter_.iter("action")}
    data_elements = [element for filter_ in filters for element in filter_.iter("data")]
    if "android.intent.category.HOME" in categories:
        home_activities.append((activity, actions, categories, data_elements))

# Exactly one Home activity. Two would mean two different home surfaces, and whichever Android
# picked would be the one the user sees.
if len(home_activities) != 1:
    err(f"{len(home_activities)} activities declare the Home category; the launcher must have "
        f"exactly one")
else:
    activity, actions, categories, data_elements = home_activities[0]
    name = attr(activity, "android:name")
    if attr(activity, "android:exported") != "true":
        err(f"the Home activity '{name}' must declare android:exported=\"true\": Android cannot "
            f"start a Home application it is not allowed to start")
    expected_actions = {"android.intent.action.MAIN"}
    expected_categories = {"android.intent.category.HOME", "android.intent.category.DEFAULT"}
    if actions != expected_actions:
        err(f"the Home activity '{name}' declares {sorted(actions - expected_actions)} beyond "
            f"ACTION_MAIN: the exported surface is the Home intent and nothing else")
    if categories != expected_categories:
        err(f"the Home activity '{name}' declares categories "
            f"{sorted(categories ^ expected_categories)} outside HOME + DEFAULT")
    if data_elements:
        err(f"the Home activity '{name}' declares a data element: a Home intent carries no URI")
    if attr(activity, "android:permission") is not None:
        err(f"the Home activity '{name}' declares a permission; the Home contract has none")

    # The manifest names a class, and that class has to exist.
    if name and name.startswith("."):
        candidate = ROOT / "app/src/main/java/com/nivara/app" / (name.lstrip(".").replace(".", "/") + ".kt")
        if not candidate.exists():
            err(f"the Home activity '{name}' has no source file at "
                f"{candidate.relative_to(ROOT)}")

    notes.append(f"launcher: one Home activity ({name}), exported for the Home intent only")

# Every other exported component still has to be a launcher entry of the application itself. A
# component exported for a custom action would be a way into Nivara that no feature asked for.
for activity in manifest_root.iter("activity"):
    if attr(activity, "android:exported") != "true":
        continue
    filters = list(activity.findall("intent-filter"))
    categories = {attr(category, "name") for filter_ in filters for category in filter_.iter("category")}
    actions = {attr(action, "name") for filter_ in filters for action in filter_.iter("action")}
    if "android.intent.category.HOME" in categories:
        continue
    if actions != {"android.intent.action.MAIN"} or "android.intent.category.LAUNCHER" not in categories:
        err(f"exported activity '{attr(activity, 'android:name')}' is neither the Home activity nor "
            f"the application's own launcher entry (actions {sorted(actions)}, categories "
            f"{sorted(categories)})")

# Providers and receivers would be new ways in; there are none, and if one appears it must be
# private like the App Lock service.
for tag in ("provider", "receiver"):
    for element in manifest_root.iter(tag):
        if attr(element, "android:exported") != "false":
            err(f"manifest {tag} '{attr(element, 'android:name')}' must declare "
                f"android:exported=\"false\"")

# Disabling another application's components is not hiding and has no place anywhere in the project.
for path in main_kt:
    text = strip_comments(path.read_text())
    for forbidden, reason in (
        ("setApplicationEnabledSetting", "Nivara never changes an application's enabled state"),
        ("setApplicationLabel", "an identity is presented through a declared component; the "
                                "platform's record of the application's own name is never rewritten"),
        ("killBackgroundProcesses", "Nivara never kills another application's process"),
        ("addPreferredActivity", "Nivara never selects a Home application: the user does"),
        ("clearPackagePreferredActivities", "Nivara never changes a launcher preference"),
        ("replacePreferredActivity", "Nivara never selects a Home application: the user does"),
    ):
        if forbidden in text:
            err(f"{path.relative_to(ROOT)}: {forbidden} — {reason}")

# A component's enabled state is changed in exactly one place, and that place only ever changes one
# of Nivara's own launcher entries. Anywhere else — and in particular anything aimed at another
# application's components — it stays forbidden.
identity_component_owner = ROOT / "app/src/main/java/com/nivara/app/data/camouflage"
for path in main_kt:
    if "setComponentEnabledSetting" in strip_comments(path.read_text()):
        if identity_component_owner not in path.parents:
            err(f"{path.relative_to(ROOT)}: setComponentEnabledSetting outside the identity "
                f"implementation — only the camouflage identity may change a component's state, and "
                f"only for Nivara's own launcher entries")

# The launcher draws what the domain's rule produced and owns no state of its own: no hidden list, no
# session, no persistence, and no second discovery scanner.
for path in launcher_sources:
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for forbidden, reason in (
        ("AtomicFiles", "the launcher goes through the hidden-application repository, never storage"),
        ("HiddenApplicationCodec", "the launcher never parses the stored hidden set"),
        ("FileHiddenApplicationRepository", "the launcher reads the contract, not the implementation"),
        ("HIDDEN_APPLICATIONS_FILE", "the launcher does not know where the hidden set is kept"),
        ("APP_HIDE_DIRECTORY", "the launcher does not know where the hidden set is kept"),
        ("ProtectedApplication", "hiding and protecting are separate dimensions"),
        ("SharedPreferences", "the launcher persists nothing, least of all a reveal"),
        ("DataStore", "the launcher persists nothing, least of all a reveal"),
        ("RoomDatabase", "the launcher persists nothing, least of all a reveal"),
        ("openFileOutput", "the launcher persists nothing, least of all a reveal"),
        ("FileOutputStream", "the launcher persists nothing, least of all a reveal"),
        ("SavedStateHandle", "a reveal must not survive the process that granted it"),
        ("rememberSaveable", "a reveal must not survive the process that granted it"),
        ("CATEGORY_HOME", "the launcher draws a home surface; it does not start another Home intent"),
        ("queryIntentActivities", "discovery happens once, in the application repository"),
        ("getInstalledApplications", "discovery happens once, in the application repository"),
        ("getInstalledPackages", "discovery happens once, in the application repository"),
        ("PackageManager", "the launcher draws what discovery produced; it does not query the platform"),
        ("UsageStatsManager", "the launcher reads no usage history"),
        ("AccessibilityService", "the launcher reads no screen"),
        ("DevicePolicyManager", "the launcher is not device administration"),
        ("java.security", "the launcher does no cryptography"),
        ("javax.crypto", "the launcher does no cryptography"),
        ("MessageDigest", "the launcher has no use for a hash"),
        ("KeyDerivationService", "key derivation belongs to the credential layer"),
        ("EncryptionService", "encryption belongs to the security layer"),
        ("Log.", "the launcher must not log: package names must never reach a log"),
        ("println(", "the launcher must not print: package names must never be logged"),
        ("HttpURLConnection", "the launcher transmits nothing"),
        ("okhttp", "the launcher transmits nothing"),
    ):
        if forbidden in text:
            err(f"{rel}: {forbidden} — {reason}")

# The launcher's session policy is the existing gate's, and nothing else. A file that reads the
# session must ask the gate for its authoritative answer, and no file may declare a session-like or
# authentication-like type of its own.
for path in launcher_sources + sorted((ROOT / "app/src/main/java/com/nivara/app/domain/launcher").glob("*.kt")):
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for match in re.finditer(
        r"^(?:internal |private |public )*(?:sealed |data |enum |abstract |open )*"
        r"(?:class|interface|object)\s+([A-Za-z_]\w*)",
        text,
        re.MULTILINE,
    ):
        name = match.group(1)
        if re.search(r"Session|Unlock|Password|Passcode|Biometric|Authenticat|Reveal[A-Za-z]*Store", name):
            err(f"{rel}: the launcher declares '{name}'; the session and its unlocked state belong "
                f"to the existing SessionManager")
    if "SessionManager" in text and "currentState()" not in text:
        err(f"{rel}: the launcher reads the session without the gate's authoritative currentState()")

# The hidden-application repository must be what the launcher depends on: the contract is the
# boundary Stage 11 was built against, and without it there is no fail-closed answer to read.
launcher_view_model = ROOT / "app/src/main/java/com/nivara/app/ui/launcher/LauncherViewModel.kt"
if not launcher_view_model.exists():
    err("the launcher view model is missing")
else:
    text = launcher_view_model.read_text()
    # Typed dependencies, not mentions: a name that only appears as a container property or an
    # argument would still leave the launcher without the contract it is supposed to depend on.
    for pattern, reason in (
        (r":\s*HiddenApplicationRepository\b",
         "the launcher must depend on the hidden-application contract"),
        (r":\s*ApplicationRepository\b",
         "the launcher draws the catalogue discovery already produced"),
        (r":\s*ApplicationLauncher\b",
         "opening an application goes through the application-launcher contract"),
    ):
        if not re.search(pattern, text):
            err(f"{launcher_view_model.relative_to(ROOT)}: {reason}")
    if "launcherCatalogue(" not in text:
        err(f"{launcher_view_model.relative_to(ROOT)}: the drawer's filtering rule belongs to the "
            f"domain layer")

launcher_rule = ROOT / "app/src/main/java/com/nivara/app/domain/launcher/LauncherCatalogue.kt"
if not launcher_rule.exists():
    err("the domain's launcher catalogue rule is missing")
else:
    text = strip_comments(launcher_rule.read_text())
    if not re.search(r"^fun launcherCatalogue\(", text, re.MULTILINE):
        err(f"{launcher_rule.relative_to(ROOT)}: the domain must declare the launcher catalogue rule "
            f"(fun launcherCatalogue)")
    for required in ("HiddenApplicationsRead.Unreadable", "HiddenApplicationsRead.Unavailable"):
        if required not in text:
            err(f"{launcher_rule.relative_to(ROOT)}: the rule must handle {required} as its own "
                f"outcome; an unreadable hidden set must never become a full catalogue")

# Hidden applications are on screen only while a reveal is; that window is protected by the
# project's single screenshot-protection implementation, as the credential and management screens
# are.
launcher_screens = sorted((ROOT / "app/src/main/java/com/nivara/app/ui/launcher").rglob("*Screen.kt"))
if not launcher_screens:
    err("no launcher screen was found")
for path in launcher_screens:
    if "SecureScreenEffect()" not in path.read_text():
        err(f"{path.relative_to(ROOT)}: the launcher screen must apply SecureScreenEffect() while a "
            f"reveal is on screen")

# Discovery is rebuilt on demand and kept in memory: no cache, no file and no database may appear
# behind it.
app_discovery_dir = ROOT / "app/src/main/java/com/nivara/app/data/app"
if not app_discovery_dir.is_dir():
    err("data/app is missing")
for path in sorted(app_discovery_dir.glob("*.kt")):
    text = strip_comments(path.read_text())
    for pattern in (r"SharedPreferences", r"DataStore", r"RoomDatabase", r"openFileOutput",
                    r"FileOutputStream", r"\bFile\("):
        if re.search(pattern, text):
            err(f"{path.relative_to(ROOT)}: application discovery persists data ({pattern})")

# every domain security contract must be implemented and wired in the composition root.
# The implementation may live anywhere under `data`, because a contract is allowed to be built
# on the platform prompt (data/biometric) rather than on the key store alone (data/security).
container = (ROOT / "app/src/main/java/com/nivara/app/di/AppContainer.kt").read_text()
contracts = sorted(p.stem for p in (ROOT / "app/src/main/java/com/nivara/app/domain/security").glob("*.kt")
                   if p.stem.endswith(("Service", "Provider", "Store", "Wrapper", "Authenticator", "Manager")))
for contract in contracts:
    if contract not in container:
        err(f"AppContainer does not expose the '{contract}' contract")
    implemented = [p for p in (ROOT / "app/src/main/java/com/nivara/app/data").rglob("*.kt")
                   if re.search(rf":\s*{contract}\b|,\s*{contract}\b", p.read_text())]
    if not implemented:
        err(f"no data-layer implementation found for '{contract}'")
# The App Lock contracts follow the same rule: exposed by the container, implemented under data.
applock_contracts = sorted(
    p.stem
    for package in ("domain/app", "domain/permissions", "domain/applock", "domain/apphide")
    for p in (ROOT / f"app/src/main/java/com/nivara/app/{package}").glob("*.kt")
    if p.stem.endswith(("Repository", "Detector", "Monitor", "Runner")))
for contract in applock_contracts:
    if contract not in container:
        err(f"AppContainer does not expose the '{contract}' contract")
    implemented = [p for p in (ROOT / "app/src/main/java/com/nivara/app/data").rglob("*.kt")
                   if re.search(rf":\s*{contract}\b|,\s*{contract}\b", p.read_text())]
    if not implemented:
        err(f"no data-layer implementation found for '{contract}'")
# The overlay host is a domain contract with a presentation-layer implementation: the domain says
# when a surface is needed, the UI knows how Android draws one. Checking it here keeps the direction
# of that dependency from being reversed later.
overlay_host_contract = ROOT / "app/src/main/java/com/nivara/app/domain/applock/AppLockOverlayHost.kt"
if not overlay_host_contract.exists():
    err("the App Lock overlay host contract is missing")
elif "AppLockOverlayHost" not in container:
    err("AppContainer does not expose the 'AppLockOverlayHost' contract")
else:
    implementations = [
        p for p in (ROOT / "app/src/main/java/com/nivara/app/ui").rglob("*.kt")
        if re.search(r":\s*AppLockOverlayHost\b", p.read_text())
    ]
    if not implementations:
        err("the App Lock overlay host has no presentation-layer implementation")

notes.append(f"security review: {len(security_sources)} security sources, {len(contracts)} contracts wired")
notes.append(f"app lock review: {len(applock_contracts)} contracts wired ({', '.join(applock_contracts)})")

# ---------------------------------------------------------------- application identity (camouflage)
# Camouflage is presentation: the name and icon of the launcher entry that starts Nivara. These rules
# pin what an identity is allowed to be — a declared component with a benign name and icon — and what
# it may never become: application hiding, a second launcher, a second authentication, a store of its
# own, or a change to Android's record of the application.
identity_domain_dir = ROOT / "app/src/main/java/com/nivara/app/domain/camouflage"
identity_data_dir = ROOT / "app/src/main/java/com/nivara/app/data/camouflage"
identity_ui_dir = ROOT / "app/src/main/java/com/nivara/app/ui/camouflage"
identity_dirs = (identity_domain_dir, identity_data_dir, identity_ui_dir)
for directory in identity_dirs:
    if not directory.is_dir():
        err(f"the application-identity layer is missing: {directory.relative_to(ROOT)}")
identity_sources = sorted(
    path for directory in identity_dirs if directory.is_dir()
    for path in directory.glob("*.kt")
)
if not identity_sources:
    err("no application-identity sources were found")

# The identities are declared once, in the domain model, and the manifest has to agree with it: an
# alias for an identity the model does not declare would be an entry nothing can select, and an
# identity without an alias would be a choice that cannot be presented.
profile_source = (identity_domain_dir / "CamouflageProfile.kt")
if not profile_source.exists():
    err("the camouflage identity model is missing")
    declared_profiles = {}
else:
    declared_profiles = dict(re.findall(
        r'^\s*([A-Z]\w*)\("([a-z][a-z0-9_]*)"\),?$',
        profile_source.read_text(),
        re.MULTILINE,
    ))
if "Nivara" not in declared_profiles:
    err("the identity model must declare Nivara's own identity")
elif declared_profiles["Nivara"] != "nivara":
    err("Nivara's own identity must use the identifier 'nivara'")
camouflage_identifiers = {name: identifier for name, identifier in declared_profiles.items()
                          if name != "Nivara"}
if not camouflage_identifiers:
    err("the identity model declares no camouflage identity")

manifest_aliases = {attr(alias, "android:name"): alias for alias in manifest_root.iter("activity-alias")}
expected_aliases = {
    f".Camouflage{name}": identifier for name, identifier in camouflage_identifiers.items()
}
missing_aliases = sorted(set(expected_aliases) - set(manifest_aliases))
extra_aliases = sorted(set(manifest_aliases) - set(expected_aliases))
if missing_aliases:
    err(f"the manifest declares no component for these identities: {missing_aliases}")
if extra_aliases:
    err(f"the manifest declares camouflage components for no declared identity: {extra_aliases}")

for name, alias in sorted(manifest_aliases.items()):
    identifier = expected_aliases.get(name)
    if identifier is None:
        continue
    # Every alias presents the same activity: an identity is not a second screen, a second task or a
    # second implementation, and only a component that already exists may be presented.
    if attr(alias, "android:targetActivity") != ".MainActivity":
        err(f"the alias '{name}' must target Nivara's own entry activity, not "
            f"'{attr(alias, 'android:targetActivity')}'")
    # Enabled-by-declaration would put every identity in the launcher on a fresh install, and the
    # default is Nivara's own identity.
    if attr(alias, "android:enabled") != "false":
        err(f"the alias '{name}' must be declared android:enabled=\"false\": the shipped default is "
            f"Nivara's own identity")
    if attr(alias, "android:exported") != "true":
        err(f"the alias '{name}' must be exported: the launcher has to be able to start it")
    if attr(alias, "android:icon") != f"@mipmap/ic_camouflage_{identifier}":
        err(f"the alias '{name}' must carry the {identifier} icon")
    if attr(alias, "android:label") != f"@string/camouflage_profile_{identifier}_label":
        err(f"the alias '{name}' must carry the {identifier} label")
    alias_actions = {attr(el, "name") for el in alias.iter("action")}
    alias_categories = {attr(el, "name") for el in alias.iter("category")}
    alias_data = [el for el in alias.iter("data")]
    if alias_actions != {"android.intent.action.MAIN"}:
        err(f"the alias '{name}' must declare exactly the launcher action MAIN, not "
            f"{sorted(alias_actions)}")
    if alias_categories != {"android.intent.category.LAUNCHER"}:
        err(f"the alias '{name}' must declare exactly the launcher category, not "
            f"{sorted(alias_categories)}")
    if alias_data:
        err(f"the alias '{name}' declares a data element: a launcher entry carries no URI")

# Nivara's own entry stays exactly what it was: enabled, exported, and labelled with the application's
# own name and icon. It is the entry that always exists, so it can never be the one a change removes.
real_entry = None
for activity in manifest_root.iter("activity"):
    if attr(activity, "android:name") == ".MainActivity":
        real_entry = activity
if real_entry is None:
    err("the manifest has no MainActivity: the application's own entry is what camouflage always keeps")
else:
    if attr(real_entry, "android:enabled") == "false":
        err("MainActivity must not be declared disabled: that is the launcher entry the user can "
            "always return to")
    if attr(real_entry, "android:exported") != "true":
        err("MainActivity must stay exported: it is the application's own entry point")
    if attr(real_entry, "android:label") != "@string/app_name":
        err("MainActivity must keep the application's own label")
    if attr(real_entry, "android:icon") not in (None, "@mipmap/ic_launcher"):
        err("MainActivity must keep the application's own icon")
    entry_categories = {attr(el, "name") for el in real_entry.iter("category")}
    if "android.intent.category.LAUNCHER" not in entry_categories:
        err("MainActivity must keep its launcher category: an identity change must never leave the "
            "application with no launcher entry at all")

# What camouflage may not touch, and what it may not become.
for path in identity_sources:
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for forbidden, reason in (
        ("LauncherActivity", "the Home contract is Stage 11's and is never part of an identity"),
        ("setApplicationEnabledSetting", "an identity is presentation, not application hiding"),
        ("setApplicationLabel", "the platform's record of the application's own name is never rewritten"),
        ("queryIntentActivities", "an identity does not enumerate anything"),
        ("getInstalledApplications", "an identity does not enumerate anything"),
        ("getInstalledPackages", "an identity does not enumerate anything"),
        ("QUERY_ALL_PACKAGES", "no package visibility is added for an identity"),
        ("HiddenApplication", "identity and hiding are separate dimensions"),
        ("ProtectedApplication", "identity and protection are separate dimensions"),
        ("ProtectedApplicationRepository", "the protected set is not the identity's business"),
        ("AppLockMonitor", "App Lock is not the identity's business"),
        ("AppLockOverlay", "the protection surface is not the identity's business"),
        ("SharedPreferences", "an identity keeps no Nivara-owned state: the platform holds it"),
        ("DataStore", "an identity keeps no Nivara-owned state: the platform holds it"),
        ("RoomDatabase", "an identity keeps no Nivara-owned state: the platform holds it"),
        ("openFileOutput", "an identity keeps no Nivara-owned state: the platform holds it"),
        ("AtomicFiles", "an identity keeps no Nivara-owned state: the platform holds it"),
        ("File(", "an identity keeps no Nivara-owned state: the platform holds it"),
        ("javax.crypto", "an identity is not cryptography and adds none"),
        ("java.security", "an identity is not cryptography and adds none"),
        ("Cipher", "an identity is not cryptography and adds none"),
        ("EncryptionService", "an identity is not cryptography and adds none"),
        ("credentialManager", "camouflage never handles a credential"),
        ("biometricAuthenticator", "camouflage never handles an authentication"),
        ("UsageStatsManager", "an identity reads no usage history"),
        ("AccessibilityService", "an identity reads no screen"),
        ("Log.", "camouflage logs nothing"),
        ("println(", "camouflage logs nothing"),
        ("HttpURLConnection", "camouflage transmits nothing"),
        ("okhttp", "camouflage transmits nothing"),
    ):
        if forbidden in text:
            err(f"{rel}: {forbidden} — {reason}")

# No second authentication, session or credential mechanism may grow inside camouflage.
for path in identity_sources:
    rel = path.relative_to(ROOT)
    text = strip_comments(path.read_text())
    for match in re.finditer(
        r"^(?:internal |private |public )*(?:sealed |data |enum |abstract |open )*"
        r"(?:class|interface|object)\s+([A-Za-z_]\w*)",
        text,
        re.MULTILINE,
    ):
        name = match.group(1)
        if re.search(r"Session|Unlock|Password|Passcode|Credential|Authenticat", name):
            err(f"{rel}: camouflage declares '{name}'; changing an identity uses the existing "
                f"SessionManager and the existing credential flow, and nothing else")

identity_view_model = (identity_ui_dir / "CamouflageViewModel.kt")
if not identity_view_model.exists():
    err("the application-identity view model is missing")
else:
    text = strip_comments(identity_view_model.read_text())
    if "SessionManager" not in text or "currentState()" not in text:
        err(f"{identity_view_model.relative_to(ROOT)}: a change must be gated on the existing "
            f"session gate through its authoritative currentState()")

# Presentation: every declared identity has a name and an icon, and the screen draws the two things
# that keep the feature honest — what it does not do, and how to get back to Nivara.
identity_strings = (res_dir / "values" / "strings.xml").read_text()
colours_source = (res_dir / "values" / "colors.xml").read_text()
for name, identifier in sorted(camouflage_identifiers.items()):
    if f'name="camouflage_profile_{identifier}_label"' not in identity_strings:
        err(f"the {name} identity has no label resource")
    if f'name="ic_camouflage_{identifier}_background"' not in colours_source:
        err(f"the {name} identity has no icon background colour")
    for kind in ("drawable", "mipmap"):
        if not (res_dir / f"{kind}-anydpi-v26" / f"ic_camouflage_{identifier}.xml").exists() \
                and not (res_dir / kind / f"ic_camouflage_{identifier}.xml").exists():
            err(f"the {name} identity has no {kind} resource (ic_camouflage_{identifier})")

identity_screen = (identity_ui_dir / "CamouflageScreen.kt")
if not identity_screen.exists():
    err("the application-identity screen is missing")
else:
    screen_text = identity_screen.read_text()
    for required, reason in (
        (r"R\.string\.camouflage_limitation(?![\w])",
         "the screen must state what an identity change does not do"),
        (r"R\.string\.camouflage_recovery(?![\w])",
         "the screen must state how to get back to Nivara"),
    ):
        if not re.search(required, screen_text):
            err(f"{identity_screen.relative_to(ROOT)}: {reason}")
    # Nivara's own identity is one of the choices, and it is offered as an identity like the others
    # rather than as a separate "turn it off" path: restoring it is the ordinary way back.
    if "R.string.app_name" not in "".join(
        path.read_text() for path in sorted(identity_ui_dir.glob("*.kt"))
    ):
        err("the identity screens must offer Nivara's own identity as a choice")

# A secret way in is refused wherever it could appear in code: recovery is an ordinary screen in the
# existing graph, and Nivara owns no dialler or special-code entry.
for path in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
    if re.search(r"\*#\*#|ACTION_DIAL|ACTION_CALL|TelephonyManager|SecretCode", path.read_text()):
        err(f"{path.relative_to(ROOT)}: Nivara has no dialler or secret-code entry — recovery is an "
            f"ordinary screen")

# The copy a user reads may not claim that camouflage cannot be found. This is checked where the
# claim would be made — the strings the product ships and the document that describes them — rather
# than over every source file, where the same words appear in honest negations.
for path in [res_dir / "values" / "strings.xml", ROOT / "docs/camouflage/README.md"]:
    text = path.read_text()
    for pattern in (r"(?i)\binvisible\b", r"(?i)\bundetectable\b", r"(?i)\buntraceable\b",
                    r"(?i)anti-forensic", r"(?i)\bguaranteed? (secrecy|hidden)\b"):
        if re.search(pattern, text):
            err(f"{path.relative_to(ROOT)}: camouflage changes presentation and may not claim that "
                f"the application cannot be found ({pattern})")

# Recovery: the identity screen is reachable from the home screen like every other settings screen,
# and the Home contract keeps working exactly as Stage 11 left it.
home_screen = ROOT / "app/src/main/java/com/nivara/app/ui/home/HomeScreen.kt"
graph_source_path = ROOT / "app/src/main/java/com/nivara/app/ui/navigation/NivaraNavHost.kt"
if "onOpenCamouflage" not in home_screen.read_text():
    err("the home screen does not offer the application-identity screen: recovery must be an "
        "ordinary route")
if "NivaraDestination.Camouflage.route" not in graph_source_path.read_text():
    err("the identity destination is not registered in the navigation graph")

# Hiding and identity stay independent in both directions: camouflage never reads or writes the
# hidden set, and nothing in the hidden-application feature reads or writes the identity.
for directory in ("app/src/main/java/com/nivara/app/domain/apphide",
                  "app/src/main/java/com/nivara/app/data/apphide",
                  "app/src/main/java/com/nivara/app/ui/apphide"):
    for path in sorted((ROOT / directory).rglob("*.kt")):
        if "Camouflage" in strip_comments(path.read_text()):
            err(f"{path.relative_to(ROOT)}: the hidden-application feature must not read the "
                f"camouflage identity: hiding and identity are separate dimensions")

notes.append(f"application identity: {len(camouflage_identifiers)} camouflage identities declared, "
             f"{len(manifest_aliases)} components in the manifest")

# Every declared destination must be registered in the navigation graph: a destination with no
# composable is a route nobody can reach, and nothing at runtime says so until it is navigated to.
destination_source = (ROOT / "app/src/main/java/com/nivara/app/ui/navigation/NivaraDestination.kt").read_text()
graph_source = (ROOT / "app/src/main/java/com/nivara/app/ui/navigation/NivaraNavHost.kt").read_text()
declared_destinations = re.findall(r"data object (\w+)\s*:\s*NivaraDestination", destination_source)
if not declared_destinations:
    err("no navigation destinations were found to check against the graph")
for name in declared_destinations:
    # Being referred to is not being registered: a destination is reachable when the graph has a
    # composable for its route, so that is what is required. (Replacing only the composable line
    # would otherwise leave a navigation call elsewhere in the file satisfying a looser check.)
    if f"composable(route = NivaraDestination.{name}.route)" not in graph_source:
        err(f"destination '{name}' is declared but has no composable in the navigation graph")

# documentation that the code refers to must exist
for doc in ("docs/crypto/envelope-format.md", "docs/crypto/README.md", "docs/apphide/README.md",
            "docs/launcher/README.md", "docs/camouflage/README.md", "docs/vault/README.md",
            "tools/crypto_reference.py"):
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

# ---------------------------------------------------------------- external encrypted vault
# The vault is a storage foundation: where it lives, whether one exists there, and creating one. These
# rules pin the boundaries the stage is built on — a domain that knows nothing about Android, all
# platform storage behind one data-layer seam, no second cryptography, no session or credential inside
# storage, no way to select a root automatically — and, above all, no state that can be read as "the
# vault is empty" when what actually happened is damage, a lost key or an unreachable folder.
vault_domain_dir = ROOT / "app/src/main/java/com/nivara/app/domain/vault"
vault_data_dir = ROOT / "app/src/main/java/com/nivara/app/data/vault"
vault_ui_dir = ROOT / "app/src/main/java/com/nivara/app/ui/vault"
for directory, names in (
    (vault_domain_dir, ["VaultState.kt", "VaultRepository.kt", "VaultLocationStore.kt", "VaultFailure.kt"]),
    (vault_data_dir, ["VaultRecordCodec.kt", "VaultRootStorage.kt", "SafVaultRootStorage.kt",
                      "FileVaultLocationStore.kt", "VaultLocationCodec.kt", "NivaraVaultRepository.kt"]),
    (vault_ui_dir, ["VaultUiState.kt", "VaultViewModel.kt", "VaultMessages.kt", "VaultScreen.kt"]),
):
    if not directory.is_dir():
        err(f"the vault layer is missing: {directory.relative_to(ROOT)}")
        continue
    for name in names:
        if not (directory / name).exists():
            err(f"the vault layer is missing {directory.relative_to(ROOT)}/{name}")

vault_domain_sources = sorted(vault_domain_dir.glob("*.kt")) if vault_domain_dir.is_dir() else []
vault_data_sources = sorted(vault_data_dir.glob("*.kt")) if vault_data_dir.is_dir() else []
vault_ui_sources = sorted(vault_ui_dir.glob("*.kt")) if vault_ui_dir.is_dir() else []
if not vault_domain_sources or not vault_data_sources or not vault_ui_sources:
    err("no vault sources were found")

# The domain is what the rest of the application reasons about, so it must not know what a platform
# is. Word boundaries are used so an identifier such as `EncryptionContext` is not mistaken for a
# context, and the check runs on code with comments removed, so documentation may discuss all of this.
for path in vault_domain_sources:
    code = strip_comments(path.read_text())
    for pattern, why in (
        (r"\bandroid\.", "an Android type"),
        (r"\bjava\.io\b", "a platform file type"),
        (r"\bjava\.net\b", "a platform network type"),
        (r"\bUri\b", "a platform URI"),
        (r"\bDocumentFile\b", "a document handle"),
        (r"\bContext\b", "an Android context"),
        (r"\bContentResolver\b", "a platform content resolver"),
        (r"\bEncryptionKey\b", "key material"),
        (r"\bSensitiveBytes\b", "raw secret bytes"),
    ):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: the vault domain must not mention {why}")

# Platform storage is one seam. Document handles are handled in two named owners and nowhere else: the
# Storage Access Framework implementation, and the store that takes the durable grant on the user's
# selection. Everything above them speaks of named children, bytes and typed failures.
saf_owner = vault_data_dir / "SafVaultRootStorage.kt"
location_owner = vault_data_dir / "FileVaultLocationStore.kt"
for owner in (saf_owner, location_owner):
    if owner.exists():
        owner_code = strip_comments(owner.read_text())
        if "DocumentsContract" not in owner_code:
            err(f"{owner.relative_to(ROOT)} must be where document access happens")
for path in vault_data_sources:
    if path.name in ("SafVaultRootStorage.kt", "FileVaultLocationStore.kt"):
        continue
    code = strip_comments(path.read_text())
    for pattern, why in (
        (r"\bDocumentsContract\b", "document access"),
        (r"\bContentResolver\b", "a content resolver"),
        (r"\bUri\b", "a platform URI"),
        (r"\bandroid\.content\.", "an Android content type"),
        (r"\bandroid\.net\.", "an Android network type"),
        (r"\bandroid\.provider\.", "an Android provider type"),
    ):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: {why} belongs to the storage seam, not to the vault's logic")

# There is one cryptographic implementation in the project, and the vault uses it. No vault source may
# reach for a primitive of its own: the ciphers, key generators, digests and randomness all live in the
# security layer, and a second copy here would be a second set of rules for the same vault.
for path in vault_domain_sources + vault_data_sources:
    code = strip_comments(path.read_text())
    for pattern in (r"\bjavax\.crypto\b", r"\bjava\.security\b", r"\bCipher\b", r"\bSecretKeySpec\b",
                    r"\bKeyGenerator\b", r"\bMessageDigest\b", r"\bMac\b", r"\bSecureRandom\b"):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: the vault must use the existing cryptographic services, not "
                f"'{pattern}'")

# The repository is where the vault's key hierarchy is assembled, and it is assembled from the Stage 2
# contracts only. It also must not touch a credential, a session or a biometric: creating a vault needs
# the session gate, and the gate belongs to the screen, not to storage.
vault_repository_source = vault_data_dir / "NivaraVaultRepository.kt"
if vault_repository_source.exists():
    repository_code = strip_comments(vault_repository_source.read_text())
    for contract in ("ContentKeyWrapper", "EncryptionService", "DeviceKeyStore", "SecureRandomGenerator",
                     "EncryptionContext"):
        if contract not in repository_code:
            err(f"the vault repository must use the existing {contract} service")
    for pattern, why in (
        (r"\bKeyDerivationService\b", "credential-based key derivation"),
        (r"\bCredentialManager\b", "the credential layer"),
        (r"\bSessionManager\b", "the session gate"),
        (r"\bBiometricAuthenticator\b", "biometrics"),
        (r"\bDeviceSecurityProvider\b", "the device security posture"),
    ):
        if re.search(pattern, repository_code):
            err(f"the vault repository must not use {why}")
    # A written record is only committed after it has been read back and opened again, and the clear
    # header's generation must agree with the sealed payload: that pair of checks is what stops an
    # edited header from promoting an older record and what stops a dropped write from looking like a
    # created vault.
    if "VerificationFailed" not in repository_code:
        err("the vault repository must verify a written record by reading it back")
    if "generation != header.generation" not in repository_code:
        err("the vault repository must check the sealed generation against the clear header")

# The screen state carries no key material and no platform handle: what is drawn is the vault's state
# and its words, and nothing that could open the vault ever reaches a Compose state.
for path in vault_ui_sources:
    code = strip_comments(path.read_text())
    for pattern, why in (
        (r"\bByteArray\b", "raw bytes"),
        (r"\bEncryptionKey\b", "key material"),
        (r"\bSensitiveBytes\b", "raw secret bytes"),
        (r"\bAtomicFiles\b", "the atomic-write mechanism"),
        (r"\bjava\.io\b", "a platform file type"),
        (r"\bContentResolver\b", "a content resolver"),
        (r"\bDocumentsContract\b", "document access"),
    ):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: the vault's presentation layer must not carry {why}")

# A session is asked for, never kept: the vault screen reads the existing gate and hands the user to the
# existing credential screen. It must not establish a session, end one, count an attempt or derive a key
# from anything the user types — there is no second vault password in this project.
vault_view_model_source = vault_ui_dir / "VaultViewModel.kt"
if vault_view_model_source.exists():
    view_model_code = strip_comments(vault_view_model_source.read_text())
    if "SessionManager" not in view_model_code:
        err("the vault screen must ask the existing session gate whether a change is authorized")
    for pattern, why in (
        (r"\bestablish\s*\(", "establish a session"),
        (r"\blockNow\s*\(", "end a session"),
        (r"\bCredentialManager\b", "the credential layer"),
        (r"\bKeyDerivationService\b", "key derivation"),
        (r"\bBiometricAuthenticator\b", "biometrics"),
    ):
        if re.search(pattern, view_model_code):
            err(f"the vault screen must not {why}: that is the existing gate's business")

# Independence: the vault has nothing to do with the name and icon Nivara presents, with the hidden set,
# with the protected set or with App Lock. Camouflage is presentation, hiding is configuration, and the
# vault is storage; a reference either way would be the beginning of a secret route.
for path in vault_domain_sources + vault_data_sources + vault_ui_sources:
    code = strip_comments(path.read_text())
    for pattern, why in (
        (r"[Cc]amouflage", "the application's identity"),
        (r"[Hh]iddenApplication", "the hidden-application set"),
        (r"\bAppLock\b", "App Lock"),
        (r"[Pp]rotectedApplication", "the protected-application set"),
        (r"\bOverlayCapability", "the overlay capability"),
    ):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: the vault must be independent of {why}")

# Nothing about storage is printed, logged or reported: a URI, a document name, a path and a vault
# identifier are all things the product never needs in output, and the vault's own rule is that they
# never reach one.
for path in vault_domain_sources + vault_data_sources + vault_ui_sources:
    code = strip_comments(path.read_text())
    for pattern in (r"\bLog\.[vdiew]\b", r"\bprintln\s*\(", r"\bSystem\.out\b"):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: the vault must not write to output ('{pattern}')")

# An exception is never swallowed. Storage trouble is reported as a typed failure, and a failed read is
# never an empty result: the difference between "I could not look" and "there is nothing there" is what
# the whole stage is built to keep.
for path in vault_domain_sources + vault_data_sources + vault_ui_sources:
    for match in re.finditer(r"catch\s*\([^)]*\)\s*\{\s*\}", strip_comments(path.read_text())):
        err(f"{path.relative_to(ROOT)}: a caught exception is ignored ({match.group(0).strip()})")

# The vault uses the Storage Access Framework, so it needs no storage permission at all. Broad storage
# permissions are not part of this application, and neither is a root chosen by Nivara itself: not a
# public directory, not the application's own private storage, and no silent migration between them.
for permission in ("MANAGE_EXTERNAL_STORAGE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
                   "READ_MEDIA_IMAGES", "READ_MEDIA_VIDEO", "READ_MEDIA_AUDIO"):
    if permission in manifest:
        err(f"the vault must not require {permission}: it uses the Storage Access Framework")
for path in vault_domain_sources + vault_data_sources:
    code = strip_comments(path.read_text())
    for pattern, why in (
        (r"getExternalStorageDirectory", "the root of external storage"),
        (r"getExternalFilesDir", "a directory the application picks by itself"),
        (r"/storage/emulated", "a hard-coded storage path"),
        (r"Environment\.DIRECTORY_", "one of Android's public directories"),
        (r"\bDownloads?\b", "the Download folder"),
        (r"\bDCIM\b", "the camera folder"),
        (r"\bPictures\b", "the pictures folder"),
        (r"[Ff]allback", "a fallback location"),
    ):
        if re.search(pattern, code):
            err(f"{path.relative_to(ROOT)}: a vault root is the user's choice, never {why}")

# The metadata is versioned and the version is checked. A record this build does not understand is
# reported as such; it is never reinterpreted as a record it does, and it is never written over.
vault_record_codec_source = vault_data_dir / "VaultRecordCodec.kt"
if vault_record_codec_source.exists():
    codec_code = strip_comments(vault_record_codec_source.read_text())
    if "VERSION" not in codec_code:
        err("the vault metadata record must carry a format version")
    if "UnsupportedVersion" not in (vault_domain_dir / "VaultState.kt").read_text():
        err("the vault must report a record it cannot read because it is newer")
    if "fileVersion" not in (vault_domain_dir / "VaultState.kt").read_text():
        err("an unsupported record must carry the version that was read")
vault_location_store_source = location_owner
if vault_location_store_source.exists():
    if "AtomicFiles" not in strip_comments(vault_location_store_source.read_text()):
        err("the stored vault location must be written through the project's atomic writer")

# The structure created is exactly what is documented: two areas and two record slots, and nothing that
# a later stage has not asked for yet.
vault_structure_source = vault_data_dir / "VaultRootStorage.kt"
if vault_structure_source.exists():
    structure_code = strip_comments(vault_structure_source.read_text())
    if "nivara.meta" not in structure_code or "nivara.content" not in structure_code:
        err("the vault must create the metadata and content areas it documents")
    if len(re.findall(r"const val \w*DIRECTORY\w*\s*[:=]", structure_code)) != 2:
        err("the vault creates exactly two directories, and this stage documents them")

# One owner per fact: the vault repository, the location store and the platform storage implementation
# are constructed in the composition root and nowhere else in the application.
for needle, defining_source, what in (
    ("NivaraVaultRepository(", "NivaraVaultRepository.kt", "the vault repository"),
    ("FileVaultLocationStore(", "FileVaultLocationStore.kt", "the vault location store"),
    ("SafVaultRootStorage(", "SafVaultRootStorage.kt", "the platform vault storage"),
):
    # The class declaring the name is not an owner of it; everything else that mentions the
    # constructor is, and there must be exactly one: the composition root.
    owners = [p for p in main_kt
              if needle in strip_comments(p.read_text()) and p.name != defining_source]
    if len(owners) != 1 or owners[0].name != "AppContainer.kt":
        err(f"{what} must be created only in the composition root (found in "
            f"{[p.name for p in owners] or 'nothing'})")

# Every state and every reason the domain declares has wording in the screen: a state with no message
# would be drawn as whatever the reader guessed, which is how "cannot be read" becomes "no vault".
vault_messages_source = vault_ui_dir / "VaultMessages.kt"
vault_state_source = vault_domain_dir / "VaultState.kt"
if vault_messages_source.exists() and vault_state_source.exists():
    messages_code = strip_comments(vault_messages_source.read_text())
    state_code = strip_comments(vault_state_source.read_text())
    for state in re.findall(r"data (?:object|class) (\w+)\s*:\s*VaultState", state_code):
        if f"VaultState.{state}" not in messages_code:
            err(f"the vault screen has no wording for the state '{state}'")
    reasons_block = re.search(r"enum class VaultUnreadable\s*\{(.*?)\}", state_code, re.S)
    for reason in re.findall(r"^\s+(\w+),$", reasons_block.group(1) if reasons_block else "", re.M):
        if f"VaultUnreadable.{reason}" not in messages_code:
            err(f"the vault screen has no wording for the reason '{reason}'")

# The vault is reached from the home screen like every other settings screen: under whatever identity
# Nivara presents, through the ordinary surface, with no hidden route of its own.
home_screen_source = (ROOT / "app/src/main/java/com/nivara/app/ui/home/HomeScreen.kt").read_text()
if "onOpenVault" not in home_screen_source:
    err("the home screen must offer the way into vault storage")

# The vault's own local suites: the state model, the identifier, the two record codecs, the repository
# with injectable storage failures, the screen's state machine and its wording.
for suite in (
    "app/src/test/java/com/nivara/app/domain/vault/VaultStateTest.kt",
    "app/src/test/java/com/nivara/app/domain/vault/VaultIdentityTest.kt",
    "app/src/test/java/com/nivara/app/data/vault/VaultRecordCodecTest.kt",
    "app/src/test/java/com/nivara/app/data/vault/VaultLocationCodecTest.kt",
    "app/src/test/java/com/nivara/app/data/vault/NivaraVaultRepositoryTest.kt",
    "app/src/test/java/com/nivara/app/ui/vault/VaultViewModelTest.kt",
    "app/src/test/java/com/nivara/app/ui/vault/VaultPresentationTest.kt",
    "app/src/androidTest/java/com/nivara/app/ui/vault/VaultScreenTest.kt",
):
    if not (ROOT / suite).exists():
        err(f"the vault's test suite is missing: {suite}")

vault_tests = sum(len(re.findall(r"@Test\b", (ROOT / suite).read_text()))
                  for suite in (
                      "app/src/test/java/com/nivara/app/domain/vault/VaultStateTest.kt",
                      "app/src/test/java/com/nivara/app/domain/vault/VaultIdentityTest.kt",
                      "app/src/test/java/com/nivara/app/data/vault/VaultRecordCodecTest.kt",
                      "app/src/test/java/com/nivara/app/data/vault/VaultLocationCodecTest.kt",
                      "app/src/test/java/com/nivara/app/data/vault/NivaraVaultRepositoryTest.kt",
                      "app/src/test/java/com/nivara/app/ui/vault/VaultViewModelTest.kt",
                      "app/src/test/java/com/nivara/app/ui/vault/VaultPresentationTest.kt",
                  ) if (ROOT / suite).exists())
notes.append(f"vault review: {len(vault_domain_sources)} domain, {len(vault_data_sources)} data and "
             f"{len(vault_ui_sources)} presentation sources; {vault_tests} local vault tests")

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
