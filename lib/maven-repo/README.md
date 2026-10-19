# Project Maven repository

Artefacts the build needs that are not on Maven Central, laid out as a Maven repository and
referenced from `pom.xml` as the `project-lib` repository.

| Artefact | Licence |
|---|---|
| `de.onvif:onvif-java-lib:2016-03-16` | Apache License 2.0, per [its source repository](https://github.com/milg0/onvif-java-lib) (the jar carries no licence of its own) |

To add one: `mvn install:install-file -Dfile=<jar> -DpomFile=<pom> -DlocalRepositoryPath=lib/maven-repo`,
then delete the `_remote.repositories` and `maven-metadata-local.xml` files it writes. Or copy the jar
and pom into the `group/artifact/version` path and add `.sha1` and `.md5` files.
