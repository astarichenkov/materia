<#
.SYNOPSIS
  Builds and starts the current Lecture Processor backend exactly once.

.DESCRIPTION
  A single, reproducible local launcher for Windows:
   - verifies Java and the Maven wrapper;
   - detects whether the target port is already owned by another process;
   - refuses to start a second Lecture Processor instance unless -Restart is used;
   - always builds the current working tree (unless -SkipBuild);
   - stamps the build with the current Git commit;
   - uses an explicit storage root (default: <repo>\data);
   - writes a log file and verifies /api/app/version after startup.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1 -Port 18090 -Restart

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1 -Stop
#>
[CmdletBinding()]
param(
    [int]$Port = 8080,
    [string]$StorageRoot = "",
    [switch]$Restart,
    [switch]$Stop,
    [switch]$SkipBuild,
    [switch]$RunTests,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

function Find-Listener {
    param([int]$LocalPort)
    try {
        $connections = Get-NetTCPConnection -LocalPort $LocalPort -State Listen -ErrorAction Stop
        $pidValue = ($connections | Select-Object -First 1).OwningProcess
        if ($pidValue) { return [int]$pidValue }
    } catch {
        # Older Windows / restricted shells: parse netstat instead.
    }
    $line = netstat -ano | Select-String -Pattern ":$LocalPort\s+.*LISTENING" | Select-Object -First 1
    if ($line) {
        $parts = ($line.ToString().Trim() -split "\s+")
        return [int]$parts[-1]
    }
    return 0
}

function Get-ProcessInfo {
    param([int]$ProcessId)
    $info = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if (-not $info) { return $null }
    return [pscustomobject]@{
        Id = $info.ProcessId
        ParentId = $info.ParentProcessId
        CommandLine = $info.CommandLine
        Created = $info.CreationDate
        IsLectureProcessor = ($info.CommandLine -match "com\.lectureprocessor\.LectureProcessorApplication|lecture-processor-backend")
    }
}

function Wait-PortFree {
    param([int]$LocalPort, [int]$Seconds = 30)
    for ($i = 0; $i -lt $Seconds; $i++) {
        if ((Find-Listener -LocalPort $LocalPort) -eq 0) { return $true }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Stop-LectureProcessor {
    param([int]$ProcessId)
    $info = Get-ProcessInfo -ProcessId $ProcessId
    if (-not $info) { return }
    if (-not $info.IsLectureProcessor) {
        throw "Отказ: процесс PID $ProcessId не является Lecture Processor. Остановите его вручную."
    }
    Write-Host "Останавливаю Lecture Processor PID $($info.Id) (started $($info.Created))"
    Stop-Process -Id $info.Id -Force
}

$listener = Find-Listener -LocalPort $Port
if ($Stop) {
    if ($listener -eq 0) {
        Write-Host "На порту $Port нет слушающего процесса."
        exit 0
    }
    Stop-LectureProcessor -ProcessId $listener
    if (Wait-PortFree -LocalPort $Port) { Write-Host "Порт $Port освобождён." }
    else { throw "Порт $Port всё ещё занят." }
    exit 0
}

if ($listener -ne 0) {
    $info = Get-ProcessInfo -ProcessId $listener
    if (-not $info) { throw "Порт $Port занят PID $listener, но процесс не найден." }
    if (-not $info.IsLectureProcessor) {
        throw "Порт $Port занят посторонним процессом PID $listener. Остановите его отдельно; launcher ничего не убивает без подтверждения."
    }
    if (-not $Restart) {
        Write-Host "Lecture Processor уже запущен: PID $($info.Id), старт $($info.Created), порт $Port."
        Write-Host "Команда: $($info.CommandLine)"
        try {
            $version = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/app/version" -TimeoutSec 5
            Write-Host "Запущенная версия: $($version.version), commit $($version.commitShort), build $($version.buildTime)"
            $current = (git rev-parse HEAD).Trim()
            if ($version.commit -ne "unknown" -and $current -and $version.commit -ne $current) {
                Write-Host "ВНИМАНИЕ: запущен commit $($version.commit), рабочее дерево $current. Нужен -Restart." -ForegroundColor Yellow
            }
        } catch {
            Write-Host "Version endpoint недоступен: $($_.Exception.Message)" -ForegroundColor Yellow
        }
        Write-Host "Для перезапуска актуальной сборки: powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1 -Port $Port -Restart"
        exit 0
    }
    Stop-LectureProcessor -ProcessId $listener
    if (-not (Wait-PortFree -LocalPort $Port)) { throw "Порт $Port не освободился после остановки." }
}

# --- toolchain -----------------------------------------------------------
$javaHome = $env:JAVA_HOME
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    $fallback = "C:\tools\jdk-25.0.2-full"
    if (Test-Path (Join-Path $fallback "bin\java.exe")) { $javaHome = $fallback }
}
if (-not $javaHome -or -not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "Java не найдена. Задайте JAVA_HOME (нужна Java 25)."
}
$env:JAVA_HOME = $javaHome
$env:PATH = (Join-Path $javaHome "bin") + ";" + $env:PATH

$mvn = Join-Path $repo "mvnw.cmd"
if (-not (Test-Path $mvn)) { throw "Не найден Maven wrapper: $mvn" }

if (-not $SkipBuild) {
    $testArg = if ($RunTests) { "verify" } else { "package -DskipTests" }
    Write-Host "Сборка актуального backend: mvnw -q -pl backend $testArg"
    & $mvn -q -pl backend @($testArg -split " ")
    if ($LASTEXITCODE -ne 0) { throw "Сборка завершилась с ошибкой (exit $LASTEXITCODE)." }
}

$jar = Get-ChildItem (Join-Path $repo "backend\target") -Filter "lecture-processor-backend-*.jar" |
    Where-Object { $_.Name -notmatch "\.original$" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $jar) { throw "JAR не найден. Запустите сборку без -SkipBuild." }

# --- build identity ------------------------------------------------------
$commit = "unknown"
try { $commit = (git rev-parse HEAD).Trim() } catch { }
$buildTime = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ssZ")
$version = "1.0.0-SNAPSHOT"
try {
    [xml]$pom = Get-Content (Join-Path $repo "backend\pom.xml")
    if ($pom.project.version) { $version = $pom.project.version }
} catch { }
$env:APP_GIT_COMMIT = $commit
$env:APP_BUILD_TIME = $buildTime
$env:APP_BUILD_VERSION = $version

# --- storage and tools ---------------------------------------------------
$legacyDb = Join-Path $repo "backend\data\lecture-processor.db"
$defaultDb = Join-Path $repo "data\lecture-processor.db"
if (-not $StorageRoot) {
    # The previous mvn spring-boot:run process used backend\data because Maven
    # runs with the module as its working directory. Prefer that database when
    # it exists so recent user lectures remain visible; never move/delete data.
    if (Test-Path $legacyDb) { $StorageRoot = Join-Path $repo "backend\data" }
    else { $StorageRoot = Join-Path $repo "data" }
}
$StorageRoot = [IO.Path]::GetFullPath($StorageRoot)
New-Item -ItemType Directory -Path $StorageRoot -Force | Out-Null
$env:LECTURE_STORAGE_ROOT = $StorageRoot
$env:SERVER_PORT = "$Port"

if ((Test-Path $legacyDb) -and (Test-Path $defaultDb) -and
        ([IO.Path]::GetFullPath($StorageRoot) -ne [IO.Path]::GetFullPath((Join-Path $repo "backend\data")))) {
    Write-Host "Примечание: существует вторая база data\lecture-processor.db; launcher её не изменяет." -ForegroundColor Yellow
}
Write-Host "Storage root: $StorageRoot (данные не удаляются и не переносятся автоматически)"

if (-not $env:FFMPEG_PATH) {
    $ffmpeg = "C:\tools\ffmpeg\ffmpeg-9.0.2-essentials_build\bin"
    if (Test-Path (Join-Path $ffmpeg "ffmpeg.exe")) {
        $env:FFMPEG_PATH = Join-Path $ffmpeg "ffmpeg.exe"
        $env:FFPROBE_PATH = Join-Path $ffmpeg "ffprobe.exe"
        $env:PATH = $ffmpeg + ";" + $env:PATH
    }
}
if (-not $env:WHISPER_PATH) {
    $whisper = "C:\tools\whisper\bin\Release\whisper-cli.exe"
    if (Test-Path $whisper) {
        $env:WHISPER_PATH = $whisper
        $env:PATH = (Split-Path -Parent $whisper) + ";" + $env:PATH
    }
}
if (-not $env:WHISPER_MODEL_PATH) {
    $model = "C:\tools\whisper\models\ggml-small.bin"
    if (Test-Path $model) { $env:WHISPER_MODEL_PATH = $model }
}
if (-not $env:OLLAMA_CHAT_MODEL) { $env:OLLAMA_CHAT_MODEL = "qwen3:1.7b" }
if (-not $env:OLLAMA_DISABLE_THINKING) { $env:OLLAMA_DISABLE_THINKING = "true" }

# --- start ---------------------------------------------------------------
$logDir = Join-Path $repo "logs"
New-Item -ItemType Directory -Path $logDir -Force | Out-Null
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$logPath = Join-Path $logDir "app-$stamp.log"
$errPath = Join-Path $logDir "app-$stamp.err.log"

Write-Host "Запуск: $($jar.FullName)"
Write-Host "Commit: $commit, build: $buildTime, storage: $StorageRoot"
$process = Start-Process -FilePath (Join-Path $javaHome "bin\java.exe") `
    -ArgumentList @("-jar", $jar.FullName) `
    -WorkingDirectory $repo `
    -RedirectStandardOutput $logPath `
    -RedirectStandardError $errPath `
    -PassThru

$ready = $false
for ($i = 0; $i -lt $TimeoutSeconds; $i++) {
    if ($process.HasExited) { break }
    try {
        $null = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/app/version" -TimeoutSec 2
        $ready = $true
        break
    } catch {
        Start-Sleep -Seconds 1
    }
}
if (-not $ready) {
    Write-Host "Приложение не ответило за $TimeoutSeconds с. Смотрите лог: $logPath" -ForegroundColor Red
    if (Test-Path $logPath) { Get-Content $logPath -Tail 40 }
    if (Test-Path $errPath) { Get-Content $errPath -Tail 40 }
    exit 1
}

$runtime = Invoke-RestMethod -Uri "http://127.0.0.1:$Port/api/app/version" -TimeoutSec 5
Write-Host ""
Write-Host "Lecture Processor готов: http://localhost:$Port/"
Write-Host "Версия: $($runtime.version) commit $($runtime.commitShort) java $($runtime.javaVersion) pid $($runtime.pid)"
Write-Host "Проверка версии: http://localhost:$Port/api/app/version"
Write-Host "Диагностика:    http://localhost:$Port/diagnostics"
Write-Host "Лог:            $logPath"
Write-Host "Остановка:      powershell -ExecutionPolicy Bypass -File scripts/run-local.ps1 -Stop -Port $Port"
