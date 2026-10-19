#!/bin/bash

platform='unknown'
unamestr=`uname`
case "$unamestr" in
	Linux)
		platform='linux'
		rootdir="$(dirname "$(readlink -f "$0")")"
	;;
	Darwin)
		platform='mac'
		rootdir="$(cd "$(dirname "$0")" && pwd -P)"
	;;
	*)
		echo "OpenPnP: unsupported platform $unamestr; use openpnp.bat on Windows." >&2
		exit 1
	;;
esac

# The jar targets Java 25; an older java would fail with UnsupportedClassVersionError.
required_java=25
java_spec="$(java -XshowSettings:properties -version 2>&1 | awk -F' = ' '/java.specification.version/ {print $2}')"
case "$java_spec" in
	1.*) java_major="${java_spec#1.}" ;;
	*) java_major="$java_spec" ;;
esac
java_major="${java_major%%[!0-9]*}"
if [ -z "$java_major" ] || [ "$java_major" -lt "$required_java" ]; then
	echo "OpenPnP requires Java $required_java or later; found ${java_spec:-no java on the PATH, or one too old to report its version}." >&2
	exit 1
fi

case "$platform" in
	mac)
		java -Xdock:name=OpenPnP --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar "$rootdir/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar"
	;;
	linux)
		java $1 --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.desktop/java.awt=ALL-UNNAMED --add-opens=java.desktop/java.awt.color=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -jar "$rootdir/target/openpnp-gui-0.0.1-alpha-SNAPSHOT.jar"
	;;
esac
