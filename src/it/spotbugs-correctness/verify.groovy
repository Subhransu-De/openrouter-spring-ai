def report = new File(basedir, 'target/spotbugs-correctness.xml')
assert report.isFile() : 'SpotBugs correctness report is missing'
def found = new groovy.xml.XmlSlurper().parse(report).BugInstance*.@type*.text() as Set
assert found.containsAll(['NP_NULL_ON_SOME_PATH', 'OBL_UNSATISFIED_OBLIGATION']) : "Unexpected detectors: ${found}"
