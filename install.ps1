# =============================================================================
# Sentient Assistant — one-line installer (Windows)
# Usage from PowerShell:
#   iwr -useb https://raw.githubusercontent.com/Aryaman-R/SentientAssistant/main/install.ps1 | iex
# Or, if you've already cloned:
#   .\install.ps1
# =============================================================================

$ErrorActionPreference = 'Stop'
$ProgressPreference    = 'Continue'

$RepoUrl     = 'https://github.com/Aryaman-R/SentientAssistant.git'
$InstallDir  = if ($env:SENTIENT_DIR) { $env:SENTIENT_DIR } else { Join-Path $HOME 'SentientAssistant' }
$Port        = if ($env:SENTIENT_PORT) { [int]$env:SENTIENT_PORT } else { 7070 }
$SkipBrowser = $env:SENTIENT_NO_BROWSER -eq '1'

function Write-Info($msg)    { Write-Host "[*] $msg"   -ForegroundColor Cyan }
function Write-Success($msg) { Write-Host "[ok] $msg"  -ForegroundColor Green }
function Write-WarnMsg($msg) { Write-Host "[!] $msg"  -ForegroundColor Yellow }
function Write-ErrorMsg($msg){ Write-Host "[X] $msg"  -ForegroundColor Red }

Write-Host @'

  +-------------------------------------------------+
  |              SENTIENT ASSISTANT                 |
  |           one-line installer + setup            |
  +-------------------------------------------------+

'@ -ForegroundColor Cyan

# ── 1. Check for winget (or fall back to choco) ──────────────────────────
$useWinget = (Get-Command winget -ErrorAction SilentlyContinue) -ne $null
$useChoco  = (Get-Command choco  -ErrorAction SilentlyContinue) -ne $null
if (-not $useWinget -and -not $useChoco) {
    Write-WarnMsg "winget not available and choco not installed. Will try direct downloads where needed."
}

# ── 2. Java 17+ ───────────────────────────────────────────────────────────
$needJava = $true
$java = Get-Command java -ErrorAction SilentlyContinue
if ($java) {
    try {
        $ver = & java -version 2>&1 | Select-String 'version' | ForEach-Object { ($_ -split '"')[1] }
        if ($ver) {
            $major = $ver.Split('.')[0]
            if ([int]$major -ge 17) {
                Write-Success "Java $ver already installed."
                $needJava = $false
            } else {
                Write-WarnMsg "Java $ver too old — installing 21."
            }
        }
    } catch {}
}
if ($needJava) {
    Write-Info "Installing Java 21 via winget…"
    if ($useWinget) {
        winget install --silent --accept-package-agreements --accept-source-agreements EclipseAdoptium.Temurin.21.JDK
    } elseif ($useChoco) {
        choco install -y temurin21
    } else {
        Write-ErrorMsg "Cannot install Java automatically. Install Java 17+ manually from https://adoptium.net and rerun."
        exit 1
    }
    # Update PATH for this session (the installer adds JAVA_HOME persistently).
    $jdk = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Filter 'jdk-21*' -ErrorAction SilentlyContinue) | Select-Object -First 1
    if ($jdk) { $env:Path = "$($jdk.FullName)\bin;$env:Path" }
    Write-Success "Java installed."
}

# ── 3. Maven (build-time only) ────────────────────────────────────────────
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Info "Installing Maven…"
    if ($useWinget) {
        winget install --silent --accept-package-agreements --accept-source-agreements Apache.Maven
    } elseif ($useChoco) {
        choco install -y maven
    } else {
        Write-WarnMsg "Could not install Maven automatically. Download from https://maven.apache.org/download.cgi and add to PATH."
        exit 1
    }
    Write-Success "Maven installed."
}

# ── 4. Git + source checkout ──────────────────────────────────────────────
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Info "Installing Git…"
    if ($useWinget) {
        winget install --silent --accept-package-agreements --accept-source-agreements Git.Git
    } elseif ($useChoco) {
        choco install -y git
    } else {
        Write-ErrorMsg "Git is required. Install from https://git-scm.com and rerun."
        exit 1
    }
}

if (Test-Path 'piassistant/pom.xml') {
    Write-Info "Running from inside an existing checkout — using current directory."
    $InstallDir = (Get-Location).Path
} elseif (Test-Path (Join-Path $InstallDir '.git')) {
    Write-Info "Updating existing checkout at $InstallDir…"
    git -C $InstallDir pull --ff-only
} else {
    Write-Info "Cloning $RepoUrl into $InstallDir…"
    git clone $RepoUrl $InstallDir
}
Set-Location $InstallDir

# ── 5. Build ──────────────────────────────────────────────────────────────
$JarPath = "piassistant\target\sentient-assistant-1.0-SNAPSHOT.jar"
if (Test-Path $JarPath) {
    Write-Success "Pre-built jar at $JarPath (skipping mvn package)."
} else {
    Write-Info "Building the fat jar (first run takes 2–5 min)…"
    Push-Location piassistant
    mvn -DskipTests -q package
    Pop-Location
    if (-not (Test-Path $JarPath)) {
        Write-ErrorMsg "Build did not produce $JarPath. Check the Maven output above."
        exit 1
    }
    Write-Success "Build complete."
}

# ── 6. .env bootstrap ─────────────────────────────────────────────────────
if (-not (Test-Path '.env')) {
    Copy-Item '.env.example' '.env'
    Write-Info ".env created from .env.example."
}

# ── 7. Launch in background + tail log ────────────────────────────────────
Write-Info "Stopping any old instance on port $Port…"
Get-Process java -ErrorAction SilentlyContinue | Where-Object {
    $_.CommandLine -and $_.CommandLine.Contains('sentient-assistant-1.0-SNAPSHOT.jar')
} | Stop-Process -Force -ErrorAction SilentlyContinue

$LogFile = Join-Path $InstallDir 'sentient.log'
Write-Info "Starting Sentient on port $Port…"
$proc = Start-Process -FilePath 'java' -ArgumentList @('-jar', $JarPath) `
    -RedirectStandardOutput $LogFile -RedirectStandardError $LogFile `
    -PassThru -NoNewWindow

# Wait up to 30s for the HTTP server to bind.
$tries = 0
while ($true) {
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/api/auth/status" -UseBasicParsing -TimeoutSec 2
        if ($r.StatusCode -eq 200) { break }
    } catch {}
    Start-Sleep -Seconds 1
    $tries++
    if ($tries -gt 30) {
        Write-ErrorMsg "Server didn't come up within 30 seconds."
        Write-Host "── Last 40 lines of $LogFile ──"
        Get-Content $LogFile -Tail 40
        exit 1
    }
}
Write-Success "Server up at http://localhost:$Port (pid $($proc.Id))."

if (-not $SkipBrowser) {
    Start-Process "http://localhost:$Port"
}

@"

  Sentient is running.

  Open http://localhost:$Port in your browser. The setup wizard
  will walk you through the rest.

  Logs:   Get-Content $LogFile -Tail 40 -Wait
  Stop:   Get-Process java | Where { `$_.CommandLine -match 'sentient-assistant' } | Stop-Process
  Re-run this script any time — it's safe.

"@ | Write-Host -ForegroundColor Green
