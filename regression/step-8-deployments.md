# Independent deployments: Step 8 regression record

**Status: Step 8 gate passed.** Baseline:
`98acf7698d5b636d3ff511af1b24e09fe658c6e9`, following Step 6 and the
[Step 7 assessment](step-7-generic-inspector.md).

The packaging project is now `deployment`, artifact `jsatdecode-deployment`.
Its `jsatdecode` and `jsatinspector` Maven profiles are both active by default.
Explicitly selecting either profile builds only its archive pair. Each ZIP and
tar.gz has its own enclosing directory, launcher pair, icon, license,
documentation and runtime `lib` directory.

- JSatDecode contains nine project modules: API, core, the two source providers,
  the two distribution providers, decoder-base and both missions, plus their
  third-party dependency closure. Its established executable name remains
  `jsatdecoder`.
- JSatInspector contains exactly five JARs: API, core, Inspector and both source
  providers. No mission decoder, decoder-base, distribution-provider module,
  third-party or native dependency is included.
- Application scripts retain their Java 25 module launch, `JAVA_HOME`/`PATH`
  selection, argument forwarding, caller directory and exit behavior.
  Core's optional `MainLauncher --inspector` route still works when Inspector is
  installed; deployment checks exercise it on JPMS and classpath.

## Verification

The clean default reactor passed with both deployments. Each profile also
packaged successfully from a fresh workspace using:

```text
mvn -o -pl deployment -am -Pjsatdecode -DskipTests package org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree -Dscope=runtime
mvn -o -pl deployment -am -Pjsatinspector -DskipTests package org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree -Dscope=runtime
```

The independent runs skip already-passed component suites, then compile and run
`DeploymentTest` directly against the selected archive. Each produces only its
requested archive pair. Maven runtime dependency trees show the intended
application closures; core's provider integration test dependencies do not enter
the Inspector archive.

The checker validates exact project-module membership, explicit descriptors,
API independence, provider isolation and service binding. It rejects unrelated
launchers/icons, checks Inspector's five-JAR limit, tests independent provider
consumers and mission DSP, and launches from paths with spaces using Windows
batch and Git POSIX shell. Legacy arguments, errors and relative input paths
retain their behavior. The tar.gz launchers retain executable permissions.

All 328 production class files across the ten Java modules are byte-identical
to the pre-change build. No configuration, decoder implementation, provider
implementation or API changed.

Build/check logs are in ignored `target/step8-jsatdecode-build.log` and
`target/step8-jsatinspector-build.log`; default clean-build and final acceptance
evidence are described in the [Step 9 report](step-9-final-migration.md).
