def report = new File(basedir, 'target/spotbugs-correctness.xml')
assert report.isFile() : 'SpotBugs correctness report is missing'
def found = new groovy.xml.XmlSlurper().parse(report).BugInstance*.@type*.text() as Set
def expected = ['NP_NULL_ON_SOME_PATH', 'NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE', 'OBL_UNSATISFIED_OBLIGATION', 'DC_DOUBLECHECK']
assert found.containsAll(expected) : "Missing detectors ${expected - found}; found ${found}"
