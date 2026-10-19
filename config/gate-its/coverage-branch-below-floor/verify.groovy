def log = new File(basedir, 'build.log').text
assert log.contains('branches covered ratio is') : 'JaCoCo did not fail on the branch coverage floor'
assert !log.contains('lines covered ratio is') : 'The line floor also failed, so this project does not isolate the branch floor'
