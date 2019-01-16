# alignment-reporter-plugin

This plugin tests a project's dependencies for 'alignment' and produces a simple text based report with the result.

Alignment is judged on whether a dependency's version matches an `alignmentPattern` which is expressed as a  regular expression.  Dependencies which
match the regular expression are consider aligned, those which don't are unaligned. In additional the report distingishes
between transitive and direct dependencies of the project.

This might be useful for organisations making use of https://github.com/release-engineering/pom-manipulation-ext to write dependency
versions.

Example usage:

```bash
mvn alignment-reporter:alignment-reporter-plugin:1.0-SNAPSHOT:report -Dscope=runtime -DalignmentPattern=myorg
```

For multi-module projects, the ``aggregate-report`` produces a single report.

```bash
mvn alignment-reporter:alignment-reporter-plugin:1.0-SNAPSHOT:aggregate-report -Dscope=runtime -DalignmentPattern=myorg
```

Example report:

```
========
mymodule
========

9 Aligned direct dependencies
------------------------------------
Aligned direct - com.fasterxml.jackson.core:jackson-core:2.9.6.myorg-00001
Aligned direct - com.fasterxml.jackson.core:jackson-databind:2.9.6.myorg-00001
Aligned direct - io.fabric8:openshift-client:4.0.4.myorg-00002
Aligned direct - io.vertx:vertx-core:3.5.3.myorg-00001
Aligned direct - io.vertx:vertx-proton:3.5.3.myorg-00001
Aligned direct - org.jboss.resteasy:resteasy-jackson2-provider:3.6.1.SP2-myorg-00001
Aligned direct - org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001
Aligned direct - org.slf4j:slf4j-api:1.7.21.myorg-00001
Aligned direct - org.slf4j:slf4j-log4j12:1.7.21.myorg-00001

1 Unaligned direct dependency
------------------------------------
Unaligned direct - com.fasterxml.jackson.module:jackson-module-jsonSchema:2.9.6

Summary - Aligned direct dependencies with unaligned transitive dependencies
----------------------------------------------------------------------------
Incompletely aligned - org.jboss.resteasy:resteasy-jackson2-provider:3.6.1.SP2-myorg-00001
Incompletely aligned - io.fabric8:openshift-client:4.0.4.myorg-00002
Incompletely aligned - io.vertx:vertx-proton:3.5.3.myorg-00001
Incompletely aligned - org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001

Detail - Aligned direct dependencies with unaligned transitive dependencies
---------------------------------------------------------------------------
Unaligned transitive - com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.7.7 <- io.fabric8:kubernetes-client:4.0.4.myorg-00002 <- io.fabric8:openshift-client:4.0.4.myorg-00002
Unaligned transitive - dk.brics.automaton:automaton:1.11-8 <- com.github.mifmif:generex:1.0.1 <- io.fabric8:kubernetes-client:4.0.4.myorg-00002 <- io.fabric8:openshift-client:4.0.4.myorg-00002
Unaligned transitive - com.github.mifmif:generex:1.0.1 <- io.fabric8:kubernetes-client:4.0.4.myorg-00002 <- io.fabric8:openshift-client:4.0.4.myorg-00002
Unaligned transitive - org.apache.qpid:proton-j:0.27.3 <- io.vertx:vertx-proton:3.5.3.myorg-00001
Unaligned transitive - com.fasterxml.jackson.jaxrs:jackson-jaxrs-base:2.9.5 <- com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.9.5 <- org.jboss.resteasy:resteasy-jackson2-provider:3.6.1.SP2-myorg-00001
Unaligned transitive - com.fasterxml.jackson.jaxrs:jackson-jaxrs-json-provider:2.9.5 <- org.jboss.resteasy:resteasy-jackson2-provider:3.6.1.SP2-myorg-00001
Unaligned transitive - com.github.fge:jackson-coreutils:1.0 <- com.github.fge:json-patch:1.3 <- org.jboss.resteasy:resteasy-jackson2-provider:3.6.1.SP2-myorg-00001
Unaligned transitive - com.github.fge:json-patch:1.3 <- org.jboss.resteasy:resteasy-jackson2-provider:3.6.1.SP2-myorg-00001
Unaligned transitive - org.jboss.spec.javax.xml.bind:jboss-jaxb-api_2.3_spec:1.0.0.Final <- org.jboss.resteasy:resteasy-jaxrs:3.6.1.SP2-myorg-00001 <- org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001
Unaligned transitive - org.reactivestreams:reactive-streams:1.0.2 <- org.jboss.resteasy:resteasy-jaxrs:3.6.1.SP2-myorg-00001 <- org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001
Unaligned transitive - org.jboss.spec.javax.annotation:jboss-annotations-api_1.2_spec:1.0.0.Final <- org.jboss.resteasy:resteasy-jaxrs:3.6.1.SP2-myorg-00001 <- org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001
Unaligned transitive - javax.activation:activation:1.1.1 <- org.jboss.resteasy:resteasy-jaxrs:3.6.1.SP2-myorg-00001 <- org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001
Unaligned transitive - commons-io:commons-io:2.5 <- org.jboss.resteasy:resteasy-jaxrs:3.6.1.SP2-myorg-00001 <- org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001
Unaligned transitive - javax.json.bind:javax.json.bind-api:1.0 <- org.jboss.resteasy:resteasy-jaxrs:3.6.1.SP2-myorg-00001 <- org.jboss.resteasy:resteasy-vertx:3.6.1.SP2-myorg-00001```