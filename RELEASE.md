
Release by running the following commands:

mvn release:prepare  # note accept the defaults
mvn release:perform -Darguments="-Dmaven.deploy.skip=true"

