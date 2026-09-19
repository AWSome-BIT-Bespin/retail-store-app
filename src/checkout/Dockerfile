# Build Stage는 Node.js 20과 Yarn으로 NestJS TypeScript 결과물을 만드는 단계입니다.
# node:20-alpine은 빌드에 필요한 Node.js 환경을 제공하는 경량 Linux Base Image입니다.
# Multi-stage build는 이 빌드 도구를 Final Stage에 넣지 않고 node_modules와 dist만 넘깁니다.
FROM node:20-alpine AS build

# WORKDIR은 이후 복사·의존성 설치·빌드 명령의 기준 경로입니다.
WORKDIR /usr/src/app

# package 파일을 node 사용자 소유로 복사합니다. 이어지는 전체 복사는 Yarn lockfile과 소스를 함께 제공합니다.
COPY --chown=node:node package*.json ./
# 이 COPY가 yarn install보다 앞에 있으므로 현재 구조에서는 소스 변경도 의존성 설치 Layer를 무효화할 수 있습니다.
COPY --chown=node:node . .

# --frozen-lockfile은 package.json과 lockfile이 일치하지 않으면 설치를 실패시켜 lockfile을 임의로 갱신하지 않습니다.
RUN yarn install --frozen-lockfile
# yarn build는 NestJS 컴파일 결과를 dist 디렉터리에 생성합니다.
RUN yarn build

# Build Stage의 기본 사용자를 node로 설정합니다. 이 Dockerfile에서는 이후 바로 Final Stage로 전환합니다.
USER node


# Final/Runtime Stage는 Amazon Linux 2023에서 Build Stage 산출물을 Node.js로 실행합니다.
# Build Stage는 Alpine, Runtime Stage는 Amazon Linux 2023이므로 복사하는 산출물은 이 실행 환경에서 호환되어야 합니다.
FROM public.ecr.aws/amazonlinux/amazonlinux:2023

# 불필요한 권장 패키지를 제외하고 Node.js와 사용자 관리 도구를 설치합니다.
RUN dnf --setopt=install_weak_deps=False install -q -y \
    nodejs20 \
    shadow-utils \
    && dnf clean all

# install_weak_deps=False는 약한 의존성의 자동 설치를 막고, dnf clean all은 같은 Layer의 패키지 메타데이터 캐시를 정리합니다.
# Amazon Linux 2023에 nodejs20을 설치해 Final Stage에서 dist/main.js를 실행합니다.
# alternatives는 일반 node 명령이 /usr/bin/node-20을 가리키도록 우선순위 90으로 등록합니다.
RUN alternatives --install /usr/bin/node node /usr/bin/node-20 90

# ENV는 이미지 실행 시 사용할 환경 변수의 기본값을 설정합니다.
ENV APPUSER=appuser
ENV APPUID=1000
ENV APPGID=1000

# APPUID=1000은 useradd의 --uid에 적용됩니다. APPGID도 1000으로 선언됐지만, 아래 useradd 명령은 GID를 직접 지정하지 않습니다.
# root가 아닌 전용 계정으로 실행해 애플리케이션 프로세스의 권한 범위를 줄입니다.
RUN useradd \
    --home "/app" \
    --create-home \
    --user-group \
    --uid "$APPUID" \
    "$APPUSER"

# [APM 추가] WhaTap이 작업 파일을 저장할 위치를 지정합니다.
ENV WHATAP_HOME=/whatap

# [APM 추가] 폴더를 만들고 앱 실행 사용자에게 소유권을 줍니다.
# Agent가 런타임 파일을 쓸 수 있도록 appuser의 UID:GID로 소유권을 설정합니다.
# Secret·Credential·Token은 Image Layer와 이력에 남을 수 있으므로 Dockerfile에 넣지 않고 실행 환경의 Secret 주입 방식을 사용합니다.
RUN mkdir -p /whatap && chown "$APPUID:$APPGID" /whatap

# WORKDIR /app은 Runtime Stage에서 복사와 실행의 기준 경로입니다.
WORKDIR /app
USER appuser

# Build 도구와 소스는 제외하고 Build Stage의 node_modules와 컴파일 결과물만 가져옵니다.
# --chown은 복사 시점에 appuser 소유로 만들어 비루트 프로세스가 파일에 접근할 수 있게 합니다.
COPY --chown=appuser:appuser --from=build /usr/src/app/node_modules ./node_modules
COPY --chown=appuser:appuser --from=build /usr/src/app/dist ./dist

# [APM 추가] WhaTap에 포함된 Linux 중계 프로그램에 실행 권한을 줍니다.
# 0555는 모든 사용자에게 읽기·실행 권한만 주고 쓰기 권한은 주지 않습니다.
RUN chmod 0555 /app/node_modules/whatap/agent/linux/*/whatap_nodejs

# Exec 형식 ENTRYPOINT는 셸을 거치지 않고 node가 dist/main.js를 컨테이너의 주 프로세스로 실행합니다.
ENTRYPOINT [ "node", "dist/main.js" ]
