"""Exercise the real security execution against compiled, never executed fixtures."""

import copy
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])
MAVEN = shutil.which("mvn")

SAFE = """
package synthetic;
public class Fixture {
    public java.sql.ResultSet query(java.sql.Connection connection, String input)
            throws java.sql.SQLException {
        var statement = connection.prepareStatement("SELECT name FROM items WHERE name = ?");
        statement.setString(1, input);
        return statement.executeQuery();
    }
    public int ordinaryBug() {
        Object value = null;
        return value.hashCode();
    }
}
"""
UNSAFE = """
package synthetic;
public class Fixture {
    public java.sql.ResultSet query(java.sql.Connection connection, String input)
            throws java.sql.SQLException {
        return connection.createStatement().executeQuery(
                "SELECT name FROM items WHERE name = '" + input + "'");
    }
}
"""


def run(directory, label, *goals, fails=False):
    result = subprocess.run(
        [MAVEN, "-B", *goals],
        cwd=directory,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    (directory / f"{label}.log").write_text(result.stdout, encoding="utf-8")
    if (result.returncode != 0) != fails:
        print(result.stdout, file=sys.stderr)
        raise RuntimeError(
            f"{label}: unexpected Maven exit code; inspect {directory / f'{label}.log'}"
        )


def report(directory, filename):
    root = ET.parse(directory / "target" / filename).getroot()
    summary = root.find("FindBugsSummary")
    if summary is None or int(summary.get("total_classes", "0")) == 0:
        raise RuntimeError("No classes were analyzed")
    errors = root.find("Errors")
    if (
        errors is None
        or int(errors.get("errors", "0"))
        or int(errors.get("missingClasses", "0"))
    ):
        raise RuntimeError(
            "Analysis errors or missing classes; inspect the harness logs"
        )
    if root.find(".//MissingClass") is not None or root.find(".//Error") is not None:
        raise RuntimeError("Incomplete analysis; inspect the harness logs")
    return {bug.get("type") for bug in root.findall("BugInstance")}


def main():
    if len(sys.argv) != 2 or MAVEN is None:
        sys.exit(
            "Usage: python security-smoke-tests/check.py <scratch-directory>; mvn must be on PATH"
        )
    scratch = Path(sys.argv[1]).resolve()
    if scratch == ROOT or ROOT in scratch.parents:
        sys.exit("Scratch directory must be outside the checkout")
    scratch.mkdir(parents=True, exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="findsecbugs-", dir=scratch))
    source_pom = ET.parse(ROOT / "pom.xml").getroot()
    properties = source_pom.find("m:properties", NS)
    plugin = copy.deepcopy(
        source_pom.find(
            "m:profiles/m:profile[m:id='security']/m:build/m:plugins/m:plugin", NS
        )
    )
    # Reuse the security plugin verbatim; only resolve root properties and fixture scope.
    values = {node.tag.split("}")[-1]: node.text for node in properties}
    values.update(
        {
            "findsecbugs.skip": "false",
            "maven.multiModuleProjectDirectory": ROOT.as_posix(),
        }
    )
    for node in plugin.iter():
        if node.text:
            for key, value in values.items():
                node.text = node.text.replace("${" + key + "}", value)
    pom = ET.fromstring(f"""
    <project xmlns="{NS["m"]}">
      <modelVersion>4.0.0</modelVersion>
      <groupId>synthetic</groupId><artifactId>security-fixture</artifactId><version>1</version>
      <properties><maven.compiler.release>17</maven.compiler.release></properties>
      <build><plugins><plugin>
        <groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <version>{values["maven-compiler-plugin.version"]}</version>
      </plugin><plugin>
        <groupId>com.github.spotbugs</groupId><artifactId>spotbugs-maven-plugin</artifactId>
        <version>{values["spotbugs-maven-plugin.version"]}</version>
      </plugin></plugins></build>
      <profiles><profile><id>security</id><build><plugins/></build></profile></profiles>
    </project>
    """)
    pom.find("m:profiles/m:profile/m:build/m:plugins", NS).append(plugin)
    ET.ElementTree(pom).write(
        directory / "pom.xml", encoding="utf-8", xml_declaration=True
    )
    for scope, name, content in (
        ("main", "Fixture", SAFE),
        ("test", "TestFixture", UNSAFE.replace("class Fixture", "class TestFixture")),
    ):
        path = directory / "src" / scope / "java" / "synthetic" / f"{name}.java"
        path.parent.mkdir(parents=True)
        path.write_text(content, encoding="utf-8")
    run(directory, "safe", "-Psecurity", "-DskipTests", "verify")
    if not (directory / "target/test-classes/synthetic/TestFixture.class").is_file():
        raise RuntimeError("Unsafe test fixture was not compiled")
    if report(directory, "findsecbugs.xml"):
        raise RuntimeError("Security execution included ordinary or test-only findings")
    run(directory, "general", "spotbugs:check", fails=True)
    if "NP_ALWAYS_NULL" not in report(directory, "spotbugsXml.xml"):
        raise RuntimeError("Security filter leaked into general SpotBugs analysis")
    (directory / "src/main/java/synthetic/Fixture.java").write_text(
        UNSAFE, encoding="utf-8"
    )
    run(directory, "unsafe", "-Psecurity", "clean", "-DskipTests", "verify", fails=True)
    if "SQL_INJECTION_JDBC" not in report(directory, "findsecbugs.xml"):
        raise RuntimeError("FindSecBugs JDBC injection detector did not run")
    print(
        "Security detection, build failure, test exclusion, and general analysis isolation verified"
    )


if __name__ == "__main__":
    main()
