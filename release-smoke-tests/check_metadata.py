"""Check effective Maven and generated Gradle publication backlinks."""

import sys
import xml.etree.ElementTree as ET

REPOSITORY = "https://github.com/Subhransu-De/openrouter-spring-ai"
EXPECTED = {
    "url": REPOSITORY,
    "scm/url": REPOSITORY,
    "scm/connection": f"scm:git:{REPOSITORY}.git",
    "scm/developerConnection": (
        "scm:git:ssh://git@github.com/Subhransu-De/openrouter-spring-ai.git"
    ),
}
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}

if len(sys.argv) < 2:
    sys.exit("Supply effective Maven or generated Gradle POM paths")

for path in sys.argv[1:]:
    root = ET.parse(path).getroot()
    for field, expected in EXPECTED.items():
        actual = root.findtext(
            "/".join(f"m:{part}" for part in field.split("/")), namespaces=NAMESPACE
        )
        if actual != expected:
            sys.exit(f"{path}: {field}: expected {expected!r}, got {actual!r}")
    print(f"Publication backlinks verified: {path}")
