#!/bin/sh
set -eu

# Secret 환경 변수가 없으면 잘못 배포된 것이므로 즉시 중단합니다.
: "${WHATAP_LICENSE:?WHATAP_LICENSE is required}"
: "${WHATAP_SERVER_HOST:?WHATAP_SERVER_HOST is required}"

export WHATAP_HOME="${WHATAP_HOME:-/whatap}"

# 읽기 전용 루트 파일시스템 대신 Helm emptyDir 볼륨에 설정·로그를 저장합니다.
umask 077
mkdir -p "$WHATAP_HOME"

cat > "$WHATAP_HOME/whatap.conf" <<EOF
license=${WHATAP_LICENSE}
whatap.server.host=${WHATAP_SERVER_HOST}
app_name=${WHATAP_APP_NAME:-catalog}
whatap.okind=${WHATAP_OKIND:-catalog}
EOF

# 컨테이너 안의 데이터 릴레이를 시작한 뒤, 계측된 Go 앱을 실행합니다.
/usr/whatap/agent/whatap-agent start

exec /app/main