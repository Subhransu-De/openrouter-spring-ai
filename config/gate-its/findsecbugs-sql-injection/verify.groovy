def report = new File(basedir, 'target/findsecbugs.xml')
assert report.isFile() : 'FindSecBugs report is missing'
def found = new groovy.xml.XmlSlurper().parse(report).BugInstance*.@type*.text() as Set
assert found.contains('SQL_INJECTION_JDBC') : "Unexpected detectors: ${found}"
