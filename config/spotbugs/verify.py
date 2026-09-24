"""Exercise the real build policies using synthetic classes outside the checkout."""

import argparse
import itertools
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])
MODULE = "openrouter-spring-ai"
POLICIES = {
    "correctness": {
        "NP_NULL_ON_SOME_PATH", "NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE",
        "OBL_UNSATISFIED_OBLIGATION", "DC_DOUBLECHECK",
    },
    "visibility": {
        "OPM_OVERLY_PERMISSIVE_METHOD", "EI_EXPOSE_REP", "EI_EXPOSE_REP2",
        "URF_UNREAD_PUBLIC_OR_PROTECTED_FIELD",
    },
    "security": {"SQL_INJECTION_JDBC"},
}


def write(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def prepare(work):
    pom = ET.parse(ROOT / "pom.xml").getroot()
    plugins = pom.find("m:build/m:plugins", NS)
    for plugin in list(plugins):
        if plugin.findtext("m:artifactId", namespaces=NS) != "maven-compiler-plugin":
            plugins.remove(plugin)
    profiles = pom.find("m:profiles", NS)
    for profile in list(profiles):
        if profile.findtext("m:id", namespaces=NS) not in POLICIES:
            profiles.remove(profile)
    modules = pom.find("m:modules", NS)
    for module in list(modules):
        if module.text != MODULE:
            modules.remove(module)
    write(work / "pom.xml", ET.tostring(pom, encoding="unicode"))
    child = ET.parse(ROOT / MODULE / "pom.xml").getroot()
    for element in list(child):
        if element.tag.rsplit("}", 1)[-1] not in {
            "modelVersion", "parent", "artifactId", "properties",
        }:
            child.remove(element)
    write(work / MODULE / "pom.xml", ET.tostring(child, encoding="unicode"))
    for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties"):
        shutil.copyfile(ROOT / name, work / name)
    # Keep the real task definitions, but no unrelated modules or dependencies.
    settings = (work / "settings.gradle.kts").read_text()
    settings = settings[:settings.index("include(")] + f'include("{MODULE}")\n'
    write(work / "settings.gradle.kts", settings)
    write(work / MODULE / "build.gradle.kts", "")
    shutil.copytree(ROOT / "config", work / "config", dirs_exist_ok=True)


def report(work, build, policy):
    if build == "gradle":
        return work / MODULE / f"build/reports/spotbugs/{policy}/spotbugs.xml"
    filename = "findsecbugs" if policy == "security" else f"spotbugs-{policy}"
    return work / MODULE / f"target/{filename}.xml"


def run(work, build, command, policies, label, bad, skipped=()):
    args = (["-B", f"-P{','.join(policies)}", "-DskipTests", "clean", "verify"]
            if build == "maven" else ["--no-daemon", "--continue", "--max-workers=2", "clean"] + [
                "spotbugs" + policy.capitalize() for policy in policies
            ])
    args += [f"-D{'findsecbugs' if p == 'security' else 'spotbugs.' + p}.skip=true"
             for p in skipped]
    with (work / f"{label}.log").open("w", encoding="utf-8") as log:
        result = subprocess.run([command, *args], cwd=work, stdout=log,
                                stderr=subprocess.STDOUT, check=False)
    blocking = bad and any(p != "visibility" and p not in skipped for p in policies)
    if (result.returncode != 0) != blocking:
        raise AssertionError(f"Unexpected exit {result.returncode}: {label}.log")
    for policy in policies:
        if policy in skipped:
            if report(work, build, policy).exists():
                raise AssertionError(f"Skipped policy still ran: {label}/{policy}")
            continue
        xml = ET.parse(report(work, build, policy)).getroot()
        errors = xml.find("Errors")
        if errors is None or errors.get("errors") != "0" or errors.get("missingClasses") != "0":
            raise AssertionError(f"Analysis errors: {label}/{policy}")
        found = {bug.get("type") for bug in xml.findall("BugInstance")}
        if bad and not POLICIES[policy] <= found:
            raise AssertionError(f"Missing detectors {POLICIES[policy] - found}: {label}/{policy}")
        if not bad and found:
            raise AssertionError(f"Good fixture reported {found}: {label}/{policy}")
        if policy != "security" and found - POLICIES[policy]:
            raise AssertionError(f"Wrong filter: {label}/{policy}: {found}")
        html = (report(work, build, policy).with_name("spotbugs.html") if build == "gradle"
                else work / MODULE / f"target/spotbugs/{policy}/spotbugs.html")
        if not html.is_file() or html.stat().st_size == 0:
            raise AssertionError(f"Missing HTML report: {label}/{policy}")
    print(f"PASS {label}", flush=True)


def failure(work, build, command, policy, label, diagnostic):
    args = (["-B", f"-P{policy}", "-DskipTests", "clean", "verify"]
            if build == "maven" else ["--no-daemon", "--max-workers=2", "clean",
                                       "spotbugs" + policy.capitalize()])
    path = work / f"{label}.log"
    with path.open("w", encoding="utf-8") as log:
        result = subprocess.run([command, *args], cwd=work, stdout=log,
                                stderr=subprocess.STDOUT, check=False)
    if result.returncode == 0 or diagnostic not in path.read_text(encoding="utf-8"):
        raise AssertionError(f"Expected {diagnostic}: {path}")
    print(f"PASS {label}", flush=True)


def broken_analysis(work, build, command, source):
    source.unlink()
    failure(work, build, command, "correctness", "Empty-scope",
            "Empty required SpotBugs correctness analysis scope")
    write(source, "package example; public class Fixture extends Missing {}\n"
                  "class Missing { public int value() { return 7; } }\n")
    # Compile a valid hierarchy, then remove a required class before analysis.
    if build == "maven":
        pom = work / MODULE / "pom.xml"
        text = pom.read_text()
        text = text.replace("</project>", """
          <build><plugins><plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-antrun-plugin</artifactId>
            <executions><execution><id>remove-synthetic-dependency</id>
              <phase>process-classes</phase><goals><goal>run</goal></goals>
              <configuration><target>
                <delete file="${project.build.outputDirectory}/example/Missing.class"/>
              </target></configuration>
            </execution></executions>
          </plugin></plugins></build></project>""")
        write(pom, text)
    else:
        write(work / MODULE / "build.gradle.kts", '''
tasks.named<JavaCompile>("compileJava") {
    doLast { destinationDirectory.file("example/Missing.class").get().asFile.delete() }
}
''')
    for policy in ("correctness", "visibility"):
        diagnostic = "analysis errors/missing classes" if build == "maven" else "failed with exit code"
        failure(work, build, command, policy, f"Missing-class-{policy}", diagnostic)
        xml = ET.parse(report(work, build, policy)).getroot()
        if "example.Missing" not in [node.text for node in xml.findall("Errors/MissingClass")]:
            raise AssertionError(f"Missing-class detector did not identify example.Missing: {policy}")
    write(source, (ROOT / "config/spotbugs/fixtures/Good.java").read_text().replace("Good", "Fixture"))
    pom = work / "pom.xml"
    tree = ET.parse(pom)
    tree.find("m:properties/m:sb-contrib.version", NS).text = "0.0.0-missing-fixture"
    tree.write(pom, encoding="unicode")
    diagnostic = ("sb-contrib:jar:0.0.0-missing-fixture" if build == "maven" else
                  "Could not find com.mebigfatguy.sb-contrib:sb-contrib:0.0.0-missing-fixture")
    failure(work, build, command, "visibility", "Unavailable-extension", diagnostic)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("build", choices=("maven", "gradle"))
    parser.add_argument("--command", required=True)
    parser.add_argument("--work-dir", required=True, type=Path)
    args = parser.parse_args()
    work = args.work_dir.resolve()
    if work == ROOT or ROOT in work.parents:
        parser.error("Use a work directory outside the repository")
    work.mkdir(parents=True, exist_ok=True)
    prepare(work)
    source = work / MODULE / "src/main/java/example/Fixture.java"
    policies = list(POLICIES) if args.build == "maven" else ["correctness", "visibility"]
    for bad in (True, False):
        name = "Bad" if bad else "Good"
        write(source, (ROOT / f"config/spotbugs/fixtures/{name}.java").read_text().replace(name, "Fixture"))
        if not bad:
            write(work / MODULE / "src/test/java/example/TestFixture.java",
                  (ROOT / "config/spotbugs/fixtures/Bad.java").read_text().replace("Bad", "TestFixture"))
        # Every profile combination detects the expected IDs. Good code passes all together.
        combinations = (combo for n in range(1, len(policies) + 1)
                        for combo in itertools.combinations(policies, n)) if bad else [policies]
        for combo in combinations:
            run(work, args.build, args.command, combo, f"{name}-{'-'.join(combo)}", bad)
        if bad and args.build == "maven":
            run(work, args.build, args.command, policies, "Skip-review", True, ("visibility",))
            run(work, args.build, args.command, policies, "Skip-required", True, ("correctness", "security"))
    broken_analysis(work, args.build, args.command, source)


if __name__ == "__main__":
    main()
