def log = new File(basedir, 'build.log').text
assert log.contains('lines covered ratio is') : 'JaCoCo did not fail on the line coverage floor'
