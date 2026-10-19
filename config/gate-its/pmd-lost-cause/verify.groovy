def log = new File(basedir, 'build.log').text
assert log.contains('PreserveStackTrace') : 'PMD did not fail on PreserveStackTrace'
