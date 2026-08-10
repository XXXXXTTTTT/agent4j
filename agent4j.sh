#!/usr/bin/env sh
set -eu
jar="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/agent-cli/target/agent4j-cli.jar"
if [ ! -f "$jar" ]; then
  printf '%s\n' '未找到 CLI JAR，请先执行 mvn -pl agent-cli -am package -DskipTests' >&2
  exit 1
fi
java_command="${JAVA_HOME:-}/bin/java"
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "$java_command" ]; then
  java_command="java"
fi
version="$($java_command -version 2>&1)"
case "$version" in
  *'version "21.'*|*'version "21"'*) ;;
  *) printf '%s\n' 'Agent4J CLI 要求 Java 21，请设置 JAVA_HOME' >&2; exit 1 ;;
esac
exec "$java_command" -Dfile.encoding=UTF-8 -jar "$jar" "$@"
