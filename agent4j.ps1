$ErrorActionPreference = "Stop"
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$jar = Join-Path $PSScriptRoot "agent-cli\target\agent4j-cli.jar"
if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) {
    throw "未找到 CLI JAR，请先执行 mvn -pl agent-cli -am package -DskipTests"
}
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "java" }
$version = (& $java -version 2>&1 | Out-String)
if ($version -notmatch 'version "21(?:\.|$)') {
    throw "Agent4J CLI 要求 Java 21，请设置 JAVA_HOME"
}
& $java '-Dfile.encoding=UTF-8' -jar $jar @args
exit $LASTEXITCODE
