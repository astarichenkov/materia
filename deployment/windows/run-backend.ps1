# Runs the Materia backend on Windows.
# Usage: ./deployment/windows/run-backend.ps1
$ErrorActionPreference = 'Stop'
$repoRoot = Resolve-Path (Join-Path $PSScriptRoot '../..')
Push-Location $repoRoot
try {
    if (-not $env:JAVA_HOME) {
        Write-Warning "JAVA_HOME is not set; point it to a JDK 25 installation."
    }
    & (Join-Path $repoRoot 'mvnw.cmd') -pl backend spring-boot:run
}
finally {
    Pop-Location
}
