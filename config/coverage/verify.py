"""Exercise the repository's coverage gates with tiny, deterministic Java fixtures."""

import argparse
import copy
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])
MODULES = (
    "openrouter-spring-ai",
    "openrouter-spring-ai-autoconfigure",
    "openrouter-spring-ai-starter",
)


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def fixture(module, branches=4, lines=5, partial=False):
    # Ten executable lines and ten branches, with no implicit constructor.
    methods = [
        f"static int b{i}(boolean b) {{ if (b) return 1; return 0; }}"
        for i in range(5)
    ] + [f"static int l{i}() {{ return {i}; }}" for i in range(5)]
    write(
        module / "src/main/java/example/Fixture.java",
        "package example;\npublic interface Fixture {\n"
        + "\n".join(methods)
        + "\n}\n",
    )
    assertions = []
    for i in range(branches):
        assertions.append(f"assertEquals(1, Fixture.b{i}(true));")
        if not partial or i != branches - 1:
            assertions.append(f"assertEquals(0, Fixture.b{i}(false));")
    assertions.extend(f"assertEquals({i}, Fixture.l{i}());" for i in range(lines))
    write(
        module / "src/test/java/example/FixtureTests.java",
        "package example;\nimport org.junit.jupiter.api.Test;\n"
        "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
        "class FixtureTests { @Test void coversExpectedPaths() {\n"
        + "\n".join(assertions)
        + "\n} }\n",
    )


def prepare(work):
    pom = ET.parse(ROOT / "pom.xml").getroot()
    # Keep the actual production coverage plugin executions and properties.
    # Other quality tools are unrelated to these intentionally tiny fixtures.
    plugins = pom.find("m:build/m:plugins", NS)
    for plugin in list(plugins):
        if plugin.findtext("m:artifactId", namespaces=NS) not in {
            "maven-compiler-plugin", "maven-surefire-plugin",
            "jacoco-maven-plugin", "maven-antrun-plugin",
        }:
            plugins.remove(plugin)
    for tag in ("profiles", "dependencies"):
        element = pom.find(f"m:{tag}", NS)
        if element is not None:
            pom.remove(element)
    modules = pom.find("m:modules", NS)
    modules.clear()
    for name in MODULES:
        ET.SubElement(modules, f"{{{NS['m']}}}module").text = name
        original = ET.parse(ROOT / name / "pom.xml").getroot()
        child = ET.Element(f"{{{NS['m']}}}project")
        for tag in ("modelVersion", "parent", "artifactId", "properties"):
            element = original.find(f"m:{tag}", NS)
            if element is not None:
                child.append(copy.deepcopy(element))
        dependencies = ET.SubElement(child, f"{{{NS['m']}}}dependencies")
        dependency = ET.SubElement(dependencies, f"{{{NS['m']}}}dependency")
        for tag, value in {
            "groupId": "org.junit.jupiter", "artifactId": "junit-jupiter",
            "version": "${junit-jupiter.version}", "scope": "test",
        }.items():
            ET.SubElement(dependency, f"{{{NS['m']}}}{tag}").text = value
        write(work / name / "pom.xml", ET.tostring(child, encoding="unicode"))
        write(
            work / name / "build.gradle.kts",
            'dependencies { testImplementation("org.junit.jupiter:junit-jupiter") }\n'
            'tasks.withType<Checkstyle>().configureEach { enabled = false }\n'
            'tasks.withType<Pmd>().configureEach { enabled = false }\n',
        )
    write(work / "pom.xml", ET.tostring(pom, encoding="unicode"))
    shutil.copyfile(ROOT / "build.gradle.kts", work / "build.gradle.kts")
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    write(work / "settings.gradle.kts", settings.replace('\t"openrouter-spring-ai-samples",\n', ""))
    fixture(work / MODULES[0])
    fixture(work / MODULES[1], branches=5)
    write(work / MODULES[2] / "src/main/java/example/package-info.java", "package example;\n")


def run(args, work, label, command, expected=None):
    print(f"Running {label}", flush=True)
    log = work / f"{label}.log"
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(
            [args.command, *command], cwd=work, stdout=output,
            stderr=subprocess.STDOUT, check=False,
        )
    output = log.read_text(encoding="utf-8", errors="replace")
    if expected is None:
        assert result.returncode == 0, f"{label} failed:\n{output}"
    else:
        assert result.returncode != 0 and expected.lower() in output.lower(), (
            f"{label} did not fail for {expected}:\n{output}"
        )
    print(f"PASS {label}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", choices=("maven", "gradle"))
    parser.add_argument("--command", required=True)
    parser.add_argument("--work-dir", required=True, type=Path)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    assert not any(work.iterdir()), "Use an empty directory for synthetic build fixtures"
    prepare(work)
    maven = args.build == "maven"
    command = ["-B", "clean", "verify"] if maven else ["--no-daemon", "clean", "check"]
    run(args, work, "boundary-and-empty-starter", command)
    report = work / MODULES[0] / (
        "target/site/jacoco/jacoco.xml" if maven
        else "build/reports/jacoco/test/jacocoTestReport.xml"
    )
    counters = {c.attrib["type"]: c.attrib for c in ET.parse(report).getroot().findall("counter")}
    assert (counters["LINE"]["covered"], counters["LINE"]["missed"]) == ("9", "1")
    assert (counters["BRANCH"]["covered"], counters["BRANCH"]["missed"]) == ("8", "2")
    # The second module stays at 100%; it cannot compensate for the first.
    fixture(work / MODULES[0], lines=4)
    run(args, work, "line-failure-with-strong-neighbor", command, "lines covered ratio")
    fixture(work / MODULES[0], partial=True)
    run(args, work, "branch-failure", command, "branches covered ratio")
    fixture(work / MODULES[0])
    fixture(work / MODULES[2], lines=4)
    run(args, work, "new-starter-code-enters-policy", command, "lines covered ratio")
    # Invoke the real guard without rerunning tests, after deleting only fixture data.
    data = "target/jacoco.exec" if maven else "build/jacoco/test.exec"
    (work / MODULES[0] / data).unlink()
    missing = (
        ["-B", "-pl", MODULES[0], "antrun:run@require-coverage-data"] if maven
        else ["--no-daemon", f":{MODULES[0]}:jacocoTestCoverageVerification", "-x", "test"]
    )
    run(args, work, "missing-execution-data", missing, "Missing JaCoCo execution data")


if __name__ == "__main__":
    main()
