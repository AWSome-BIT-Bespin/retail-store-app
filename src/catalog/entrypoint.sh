#!/bin/sh
set -eu

# 설정 파일을 현재 사용자만 읽고 쓸 수 있게 만듭니다.
umask 077

# 두 값이 모두 없으면 WhaTap 수집을 끄고 앱을 실행합니다.
if [ -z "${WHATAP_LICENSE:-}" ] && [ -z "${WHATAP_SERVER_HOST:-}" ]; then
    export WHATAP_HOME="/tmp/whatap-disabled"
    export WHATAP_CONFIG_HOME="$WHATAP_HOME"
    export WHATAP_CONFIG="whatap.conf"

    mkdir -p "$WHATAP_HOME"
    printf 'enabled=false\n' > "$WHATAP_HOME/$WHATAP_CONFIG"

    exec /app/main
fi

# 한 값만 있다면 불완전한 설정이므로 실행을 중단합니다.
: "${WHATAP_LICENSE:?WHATAP_LICENSE is required}"
: "${WHATAP_SERVER_HOST:?WHATAP_SERVER_HOST is required}"

# 설정과 PID 파일은 쓰기 가능한 볼륨에 저장합니다.
export WHATAP_HOME="${WHATAP_HOME:-/whatap}"
export WHATAP_CONFIG_HOME="$WHATAP_HOME"
export WHATAP_CONFIG="whatap.conf"
export WHATAP_PID_FILE="$WHATAP_HOME/whatap_agent.pid"
export WHATAP_APP_TYPE="8"

# WhaTap을 사용하는 경우 기존 OTel 계측을 끕니다.
export OTEL_ENABLED="false"

mkdir -p "$WHATAP_HOME"
cat > "$WHATAP_HOME/$WHATAP_CONFIG" <<EOF
license=${WHATAP_LICENSE}
whatap.server.host=${WHATAP_SERVER_HOST}
app_name=${WHATAP_APP_NAME:-catalog}
whatap.okind=${WHATAP_OKIND:-catalog}
EOF

# RPM 서비스 정의의 옵션으로 중계 프로그램을 백그라운드 실행합니다.
# whatap-agent(하이픈)가 아닌 whatap_agent(밑줄)입니다.
/usr/whatap/agent/whatap_agent -t=8 -d=1

# 셸을 Go 앱으로 교체해 컨테이너 종료 신호가 앱에 전달되게 합니다.
exec /app/main