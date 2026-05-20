#!/usr/bin/env bash
# =============================================================================
# Sentient Assistant — one-line installer (macOS + Linux)
# Usage:
#   curl --proto '=https' --tlsv1.2 -fsSL https://raw.githubusercontent.com/Aryaman-R/SentientAssistant/main/install.sh | bash
# Or, if you've already cloned the repo:
#   bash install.sh
# =============================================================================
set -euo pipefail

REPO_URL="https://github.com/Aryaman-R/SentientAssistant.git"
INSTALL_DIR="${SENTIENT_DIR:-$HOME/SentientAssistant}"
PORT="${SENTIENT_PORT:-7070}"
SKIP_BROWSER="${SENTIENT_NO_BROWSER:-0}"

# ── Colours ───────────────────────────────────────────────────────────────
if [ -t 1 ]; then
    BOLD=$(printf '\033[1m'); GREEN=$(printf '\033[0;32m'); RED=$(printf '\033[0;31m')
    CYAN=$(printf '\033[0;36m'); YELLOW=$(printf '\033[1;33m'); RESET=$(printf '\033[0m')
else
    BOLD=; GREEN=; RED=; CYAN=; YELLOW=; RESET=;
fi
info()    { printf "%s[*]%s %s\n" "$CYAN"   "$RESET" "$*"; }
success() { printf "%s[ok]%s %s\n" "$GREEN"  "$RESET" "$*"; }
warn()    { printf "%s[!]%s %s\n" "$YELLOW" "$RESET" "$*"; }
error()   { printf "%s[X]%s %s\n" "$RED"    "$RESET" "$*" 1>&2; }

# ── Banner ────────────────────────────────────────────────────────────────
cat <<'BANNER'

  ╭──────────────────────────────────────────────────╮
  │              SENTIENT ASSISTANT                  │
  │           one-line installer + setup             │
  ╰──────────────────────────────────────────────────╯

BANNER

# ── 1. Detect OS / arch ───────────────────────────────────────────────────
OS="$(uname -s)"
ARCH="$(uname -m)"
case "$OS" in
    Darwin*) OS_TAG="macos" ;;
    Linux*)  OS_TAG="linux" ;;
    *)
        error "Unsupported OS: $OS. Use install.ps1 on Windows."
        exit 1
        ;;
esac
info "Detected: $OS_TAG / $ARCH"

# ── 2. Install Java 17+ if missing ────────────────────────────────────────
need_java=1
if command -v java >/dev/null 2>&1; then
    JV=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
    if [ "${JV:-0}" -ge 17 ] 2>/dev/null; then
        success "Java $JV is already installed."
        need_java=0
    else
        warn "Java $JV found — upgrading to 17+."
    fi
fi

if [ "$need_java" = "1" ]; then
    info "Installing Java 17 (openjdk-21 on Linux, openjdk@21 on macOS)…"
    if [ "$OS_TAG" = "macos" ]; then
        if ! command -v brew >/dev/null 2>&1; then
            error "Homebrew not found. Install from https://brew.sh and rerun."
            exit 1
        fi
        brew install openjdk@21
        # Symlink so /usr/bin/java picks it up
        sudo ln -sfn "$(brew --prefix)/opt/openjdk@21/libexec/openjdk.jdk" \
            /Library/Java/JavaVirtualMachines/openjdk-21.jdk 2>/dev/null || true
    else
        if command -v apt-get >/dev/null 2>&1; then
            sudo apt-get update -y
            sudo apt-get install -y openjdk-21-jdk-headless || sudo apt-get install -y openjdk-17-jdk-headless
        elif command -v dnf >/dev/null 2>&1; then
            sudo dnf install -y java-21-openjdk-devel || sudo dnf install -y java-17-openjdk-devel
        elif command -v yum >/dev/null 2>&1; then
            sudo yum install -y java-21-openjdk-devel || sudo yum install -y java-17-openjdk-devel
        elif command -v pacman >/dev/null 2>&1; then
            sudo pacman -Sy --noconfirm jdk21-openjdk
        else
            error "Could not detect a package manager. Install Java 17+ manually and rerun."
            exit 1
        fi
    fi
    success "Java installed."
fi

# ── 3. Install Maven if missing (build-time only) ─────────────────────────
if ! command -v mvn >/dev/null 2>&1; then
    info "Installing Maven (needed only for first-time build)…"
    if [ "$OS_TAG" = "macos" ]; then
        brew install maven
    else
        if command -v apt-get >/dev/null 2>&1; then
            sudo apt-get install -y maven
        elif command -v dnf >/dev/null 2>&1; then
            sudo dnf install -y maven
        elif command -v yum >/dev/null 2>&1; then
            sudo yum install -y maven
        elif command -v pacman >/dev/null 2>&1; then
            sudo pacman -S --noconfirm maven
        else
            error "Could not install Maven automatically. Install it and rerun."
            exit 1
        fi
    fi
    success "Maven installed."
fi

# ── 4. Get the source ─────────────────────────────────────────────────────
if [ -d "piassistant" ] && [ -f "pom.xml" -o -f "piassistant/pom.xml" ]; then
    info "Already in a SentientAssistant checkout — using current directory."
    INSTALL_DIR="$(pwd)"
elif [ -d "$INSTALL_DIR/.git" ]; then
    info "Updating existing checkout at $INSTALL_DIR…"
    git -C "$INSTALL_DIR" pull --ff-only || warn "git pull failed; using existing checkout."
else
    if ! command -v git >/dev/null 2>&1; then
        error "git is required. Install it and rerun."
        exit 1
    fi
    info "Cloning $REPO_URL into $INSTALL_DIR…"
    git clone "$REPO_URL" "$INSTALL_DIR"
fi
cd "$INSTALL_DIR"

# ── 5. Build the fat jar ──────────────────────────────────────────────────
JAR_PATH="piassistant/target/sentient-assistant-1.0-SNAPSHOT.jar"
if [ -f "$JAR_PATH" ]; then
    success "Pre-built jar found at $JAR_PATH (skipping mvn package)."
else
    info "Building the fat jar (mvn package — first run can take 2–5 minutes)…"
    (cd piassistant && mvn -DskipTests -q package)
    if [ ! -f "$JAR_PATH" ]; then
        error "Build did not produce $JAR_PATH. Check the Maven output above."
        exit 1
    fi
    success "Build complete."
fi

# ── 6. Bootstrap .env if missing ──────────────────────────────────────────
if [ ! -f ".env" ]; then
    cp .env.example .env
    info ".env created from .env.example. The wizard will help you fill it in."
fi

# ── 7. Launch (background) + tail log ─────────────────────────────────────
LOG_FILE="$INSTALL_DIR/sentient.log"
info "Starting Sentient on port $PORT…"
# Kill any old instance bound to the same port (so re-running this script Just Works).
pkill -f "sentient-assistant-1.0-SNAPSHOT.jar" 2>/dev/null || true
nohup java -jar "$JAR_PATH" > "$LOG_FILE" 2>&1 &
PID=$!
disown "$PID" 2>/dev/null || true

# Wait up to 30 seconds for it to start serving.
TRIES=0
until curl --silent --fail "http://localhost:$PORT/api/auth/status" >/dev/null 2>&1; do
    sleep 1
    TRIES=$((TRIES+1))
    if [ "$TRIES" -gt 30 ]; then
        error "Server didn't come up within 30 seconds."
        echo "── Last 40 lines of $LOG_FILE ──"
        tail -n 40 "$LOG_FILE" || true
        exit 1
    fi
done
success "Server up at http://localhost:$PORT (pid $PID)."

# ── 8. Open browser to the wizard ─────────────────────────────────────────
if [ "$SKIP_BROWSER" != "1" ]; then
    URL="http://localhost:$PORT"
    if [ "$OS_TAG" = "macos" ]; then
        open "$URL" 2>/dev/null || true
    else
        if command -v xdg-open >/dev/null 2>&1; then xdg-open "$URL" >/dev/null 2>&1 & fi
    fi
fi

cat <<EOF

  ${GREEN}Sentient is running.${RESET}

  Open ${BOLD}http://localhost:$PORT${RESET} in your browser. The setup
  wizard will walk you through the rest — installing the AI engine,
  setting a password, and connecting any integrations you want.

  Logs:   tail -f $LOG_FILE
  Stop:   pkill -f sentient-assistant-1.0-SNAPSHOT.jar
  Restart this script any time — it's safe to re-run.

EOF
