#!/bin/bash
# Двойной клик по этому файлу запускает игру.
# Finder открывает .command в Terminal с домашним каталогом и урезанным PATH,
# поэтому всё — каталог, Java, порт — определяем здесь сами.

cd "$(dirname "$0")" || exit 1
PROJECT_DIR="$(pwd)"

BOLD=$'\033[1m'; DIM=$'\033[2m'; OFF=$'\033[0m'
GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; CYAN=$'\033[36m'

say()  { printf '%s\n' "$*"; }
step() { printf '%s▸%s %s\n' "$CYAN" "$OFF" "$*"; }
warn() { printf '%s!%s %s\n' "$YELLOW" "$OFF" "$*"; }

die() {
    printf '\n%s✖ %s%s\n\n' "$RED" "$*" "$OFF"
    say "Окно не закроется само — прочитайте сообщение выше."
    say "Нажмите Enter, чтобы закрыть."
    read -r _
    exit 1
}

clear
cat <<'BANNER'
  ╭──────────────────────────────────────────────╮
  │   ՀԱՅԿԱԿԱՆ ԱԼԻԱՍ  ·  Армянский Алиас         │
  ╰──────────────────────────────────────────────╯
BANNER
say ""

# ── 1. Java ────────────────────────────────────────────────────────────────
# Проекту нужна Java 21+. java_home знает про все установленные JDK,
# даже если java нет в PATH (а при запуске из Finder её там обычно нет).
JAVA_HOME_FOUND=""
for want in 21 22 23 24 25; do
    candidate="$(/usr/libexec/java_home -v "$want" 2>/dev/null)"
    if [ -n "$candidate" ] && [ -x "$candidate/bin/java" ]; then
        JAVA_HOME_FOUND="$candidate"
        break
    fi
done
if [ -z "$JAVA_HOME_FOUND" ] && command -v java >/dev/null 2>&1; then
    JAVA_HOME_FOUND="$(dirname "$(dirname "$(command -v java)")")"
fi
[ -n "$JAVA_HOME_FOUND" ] || die "Не найдена Java 21 или новее. Установите JDK 21: https://adoptium.net"

export JAVA_HOME="$JAVA_HOME_FOUND"
export PATH="$JAVA_HOME/bin:$PATH"
step "Java: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"

# ── Уборка ─────────────────────────────────────────────────────────────────
# Ставим ловушку ДО сборки и запуска: окно могут закрыть в любой момент,
# в том числе пока идёт долгая первая сборка.
SERVER_PID=""
cleaned=no
cleanup() {
    [ "$cleaned" = yes ] && return
    cleaned=yes
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        say ""
        step "Останавливаю сервер…"
        kill -TERM "$SERVER_PID" 2>/dev/null
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$SERVER_PID" 2>/dev/null || break
            sleep 1
        done
        # не послушался — добиваем, чтобы порт не остался занят
        kill -0 "$SERVER_PID" 2>/dev/null && kill -KILL "$SERVER_PID" 2>/dev/null
    fi
    say "До встречи! 🎭"
}
trap cleanup EXIT
trap 'cleanup; exit 0' INT TERM HUP

# ── 2. Порт ────────────────────────────────────────────────────────────────
# Это обязательно ДО сборки: работающий сервер держит jar открытым, и пересборка
# поверх него ломает уже идущую игру.
is_our_app() {  # отвечает ли на порту именно наша игра
    curl -s --max-time 2 "http://127.0.0.1:$1/" 2>/dev/null | grep -q 'ԱԼԻԱՍ'
}

PORT=8080
REUSED=no
while :; do
    if ! lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
        break                      # порт свободен
    fi
    if is_our_app "$PORT"; then
        REUSED=yes                 # игра уже запущена — просто откроем её
        break
    fi
    warn "Порт $PORT занят другой программой, пробую следующий…"
    PORT=$((PORT + 1))
    [ "$PORT" -le 8090 ] || die "Не нашёл свободный порт в диапазоне 8080–8090."
done

# ── 3. Сборка (только если нужно и только если ничего не запущено) ─────────
JAR=""
if [ "$REUSED" = no ]; then
    JAR="$(ls target/ArmenianAlias-*.jar 2>/dev/null | head -1)"

    needs_build=no
    if [ -z "$JAR" ] || [ ! -f "$JAR" ]; then
        needs_build=yes
    elif [ -n "$(find src -type f -newer "$JAR" -print -quit 2>/dev/null)" ]; then
        needs_build=yes
    elif [ pom.xml -nt "$JAR" ]; then
        needs_build=yes
    fi

    if [ "$needs_build" = yes ]; then
        step "Собираю приложение — первый раз это занимает пару минут…"
        chmod +x ./mvnw 2>/dev/null
        if ! ./mvnw -q -B -DskipTests package; then
            die "Сборка не удалась. Прокрутите вверх — там сообщение Maven."
        fi
        JAR="$(ls target/ArmenianAlias-*.jar 2>/dev/null | head -1)"
        [ -n "$JAR" ] || die "Сборка прошла, но jar-файл не найден в target/."
        step "Готово."
    else
        step "Сборка свежая — пересобирать нечего."
    fi
fi

# ── 4. Адрес для телефонов ─────────────────────────────────────────────────
lan_ip() {
    local ip
    for iface in $(route -n get default 2>/dev/null | awk '/interface:/{print $2}') en0 en1 en2; do
        ip="$(ipconfig getifaddr "$iface" 2>/dev/null)"
        [ -n "$ip" ] && { printf '%s' "$ip"; return; }
    done
}
IP="$(lan_ip)"

# ── 5. Запуск ──────────────────────────────────────────────────────────────
if [ "$REUSED" = yes ]; then
    step "Игра уже запущена на порту $PORT — открываю её."
else
    step "Запускаю сервер на порту $PORT…"
    LOG="$PROJECT_DIR/target/alias-run.log"
    "$JAVA_HOME/bin/java" -jar "$JAR" --server.port="$PORT" > "$LOG" 2>&1 &
    SERVER_PID=$!

    printf '  '
    for _ in $(seq 1 90); do
        if is_our_app "$PORT"; then break; fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            say ""
            say "$DIM--- последние строки лога ---$OFF"
            tail -25 "$LOG"
            die "Сервер завершился при запуске. Лог: $LOG"
        fi
        printf '.'
        sleep 1
    done
    say ""
    is_our_app "$PORT" || die "Сервер не ответил за 90 секунд. Лог: $LOG"
fi

# ── 6. Куда заходить ───────────────────────────────────────────────────────
say ""
say "${GREEN}${BOLD}  ╭────────────────────────────────────────────╮${OFF}"
say "${GREEN}${BOLD}  │             ИГРА ЗАПУЩЕНА ✓                │${OFF}"
say "${GREEN}${BOLD}  ╰────────────────────────────────────────────╯${OFF}"
say ""
if [ -n "$IP" ]; then
    say "   ${BOLD}С телефонов (та же Wi-Fi сеть):${OFF}"
    say "   ${CYAN}${BOLD}http://$IP:$PORT${OFF}"
    say ""
fi
say "   ${DIM}На этом компьютере: http://localhost:$PORT${OFF}"
say ""
say "   Первый, кто откроет сайт, становится админом"
say "   и раздаёт игроков по командам."
say ""
say "${DIM}   ────────────────────────────────────────────${OFF}"
say "${DIM}   Чтобы остановить игру: Ctrl+C или закройте это окно.${OFF}"
if [ -z "$IP" ]; then
    warn "Не удалось определить адрес в сети — похоже, Wi-Fi выключен."
fi
say ""

open "http://localhost:$PORT" 2>/dev/null

# держим окно живым, пока работает сервер
if [ -n "$SERVER_PID" ]; then
    wait "$SERVER_PID"
else
    say "${DIM}Сервер запущен отдельно; это окно можно закрыть.${OFF}"
    say "Нажмите Enter, чтобы закрыть."
    read -r _
fi
