"""Verify PMD boundaries, source scope, and blocking/report-only build wiring."""

import argparse
import copy
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PMD_VERSION = ET.parse(ROOT / "pom.xml").findtext("m:properties/m:pmd.version", namespaces=NS)
ET.register_namespace("", NS["m"])
CORE = "openrouter-spring-ai"
MODULES = (CORE, "openrouter-spring-ai-autoconfigure", "openrouter-spring-ai-starter",
           "openrouter-spring-ai-samples")


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def prepare(work):
    # Copy the real PMD executions, distribution, and Gradle task wiring. Remove
    # unrelated gates so an intentional fixture violation can fail only PMD.
    pom = ET.parse(ROOT / "pom.xml").getroot()
    plugins = pom.find("m:build/m:plugins", NS)
    for plugin in list(plugins):
        if plugin.findtext("m:artifactId", namespaces=NS) not in {
            "maven-compiler-plugin", "maven-surefire-plugin", "maven-pmd-plugin",
        }:
            plugins.remove(plugin)
    for tag in ("profiles", "dependencies"):
        element = pom.find(f"m:{tag}", NS)
        if element is not None:
            pom.remove(element)
    # Repeated synthetic branches are deliberate; CPD policy is outside this test.
    ET.SubElement(pom.find("m:properties", NS), f"{{{NS['m']}}}cpd.skip").text = "true"
    write(work / "pom.xml", ET.tostring(pom, encoding="unicode"))
    for name in MODULES:
        original = ET.parse(ROOT / name / "pom.xml").getroot()
        child = ET.Element(f"{{{NS['m']}}}project")
        for tag in ("modelVersion", "parent", "artifactId"):
            child.append(copy.deepcopy(original.find(f"m:{tag}", NS)))
        dependencies = ET.SubElement(child, f"{{{NS['m']}}}dependencies")
        dependency = ET.SubElement(dependencies, f"{{{NS['m']}}}dependency")
        for tag, value in {"groupId": "org.junit.jupiter", "artifactId": "junit-jupiter",
                           "version": "${junit-jupiter.version}", "scope": "test"}.items():
            ET.SubElement(dependency, f"{{{NS['m']}}}{tag}").text = value
        write(work / name / "pom.xml", ET.tostring(child, encoding="unicode"))
        write(work / name / "build.gradle.kts", '''
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.gradle.testing.jacoco.tasks.JacocoCoverageVerification
dependencies { testImplementation("org.junit.jupiter:junit-jupiter") }
tasks.withType<Checkstyle>().configureEach { enabled = false }
tasks.withType<JacocoReport>().configureEach { enabled = false }
tasks.withType<JacocoCoverageVerification>().configureEach { enabled = false }
tasks.matching { it.name == "requireCoverageData" }.configureEach { enabled = false }
''')
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
        if (ROOT / name).exists():
            shutil.copyfile(ROOT / name, work / name)
    shutil.copytree(ROOT / "config/pmd", work / "config/pmd")


def source(work, name, body, module=CORE, test=False):
    path = work / module / f"src/{'test' if test else 'main'}/java/example/{name}.java"
    write(path, "package example;\n" + body + "\n")
    return path


def cognitive(score):
    # Six nested conditions cost 1+2+3+4+5+6=21; flat conditions add one each.
    body = "if (x > 0) {" * 6 + "x--;" + "}" * 6
    body += "if (x > 0) { x--; }" * (score - 21)
    return f"class Cognitive {{ int value(int x) {{ {body} return x; }} }}"


def cyclomatic(score):
    body = "if (x > 0) { x--; }" * (score - 1)
    return f"class Cyclomatic {{ int value(int x) {{ {body} return x; }} }}"


def class_complexity(score):
    # Each method costs one, plus the explicit constructor.
    methods = "\n".join(f"int value{i}() {{ return {i}; }}" for i in range(score - 1))
    return f"class ClassComplexity {{ ClassComplexity() {{ }} {methods} }}"


def run(args, work, label, goals, expected=()):
    print(f"Running {label}", flush=True)
    log = work / f"{label}.log"
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run([args.command, *goals], cwd=work, stdout=output,
                                stderr=subprocess.STDOUT, check=False)
    reports = list(work.glob("*/target/pmd.xml")) if args.build == "maven" else list(
        work.glob("*/build/reports/pmd/*.xml"))
    found = set()
    for report in reports:
        tree = ET.parse(report)
        assert tree.getroot().attrib["version"] == PMD_VERSION, f"Wrong PMD distribution in {report}"
        errors = tree.findall(".//{*}error") + tree.findall(".//{*}configerror")
        assert not errors, f"PMD analysis error in {report}"
        for file in tree.findall("{*}file"):
            for violation in file.findall("{*}violation"):
                found.add((Path(file.attrib["name"]).name, violation.attrib["rule"]))
    output = log.read_text(encoding="utf-8", errors="replace")
    assert found == set(expected), f"{label}: expected {expected}, got {found}\n{output}"
    assert (result.returncode != 0) == bool(expected), f"{label}: unexpected exit\n{output}"
    assert reports, f"{label}: no PMD reports"
    print(f"PASS {label}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", choices=("maven", "gradle"))
    parser.add_argument("--command", required=True)
    parser.add_argument("--work-dir", required=True, type=Path)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert not any(work.iterdir()), "Use an empty directory for synthetic fixtures"
    prepare(work)
    goals = ["-B", "verify"] if args.build == "maven" else ["--no-daemon", "--max-workers=2", "check"]
    boundaries = (
        ("Cognitive", cognitive, 25, "CognitiveComplexity"),
        ("Cyclomatic", cyclomatic, 15, "CyclomaticComplexity"),
        ("ClassComplexity", class_complexity, 80, "CyclomaticComplexity"),
    )
    for offset in (-1, 0, 1):
        expected = []
        paths = []
        for name, factory, boundary, rule in boundaries:
            score = boundary + offset
            path = source(work, name, factory(score))
            paths.append(path)
            if offset >= 0:
                expected.append((path.name, rule))
        run(args, work, f"boundaries-{offset:+}", goals, expected)
    for path in paths:
        path.unlink()
    # Production complexity is excluded from tests and samples. Ordinary test
    # checks still apply to classes whose names do not end in Test/Tests.
    source(work, "Cognitive", cognitive(26), test=True)
    source(work, "Cyclomatic", cyclomatic(16), module="openrouter-spring-ai-samples")
    source(work, "FixtureTests", "class FixtureTests { @org.junit.jupiter.api.Test void test() {} }", test=True)
    run(args, work, "source-scope", goals)
    fixtures = (
        ("LostCause", ('class LostCause { void run() { try { throw new Exception(); } '
         'catch (Exception ex) { throw new IllegalStateException("lost"); } } }'), "PreserveStackTrace", False),
        ("Modifiers", "interface Modifiers { public abstract void run(); }", "UnnecessaryModifier", False),
        ("PublicTest", "public class PublicTest { @org.junit.jupiter.api.Test public void test() {} }",
         "JUnitJupiterTestShouldBePackagePrivate", True),
    )
    paths = []
    expected = []
    for name, body, rule, test in fixtures:
        path = source(work, name, body, test=test)
        paths.append(path)
        expected.append((path.name, rule))
    run(args, work, "recommended-rules", goals, expected)
    for path in paths:
        path.unlink()
    # Public count stays below class complexity but exceeds the advisory threshold.
    methods = "\n".join(f"public int value{i}() {{ return {i}; }}" for i in range(46))
    source(work, "PublicApi", f"public class PublicApi {{ {methods} }}")
    run(args, work, "public-api-does-not-block", goals)
    review = (["-B", "pmd:pmd@pmd-review"] if args.build == "maven"
              else ["--no-daemon", "--max-workers=2", f":{CORE}:pmdPublicApiReview"])
    log = work / "review.log"
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run([args.command, *review], cwd=work, stdout=output,
                                stderr=subprocess.STDOUT, check=False)
    assert result.returncode == 0, log.read_text(encoding="utf-8", errors="replace")
    report = work / CORE / ("target/pmd-review/pmd.xml" if args.build == "maven"
                            else "build/reports/pmd/pmdPublicApiReview.xml")
    violations = ET.parse(report).findall(".//{*}violation")
    assert [v.attrib["rule"] for v in violations] == ["ExcessivePublicCount"]
    # The optional report must not replace or contaminate the blocking report.
    report.unlink()
    path = source(work, "Cyclomatic", cyclomatic(15))
    run(args, work, "blocking-after-review", goals, [(path.name, "CyclomaticComplexity")])


if __name__ == "__main__":
    main()
