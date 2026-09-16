FROM node:20-alpine AS build

WORKDIR /usr/src/app

COPY --chown=node:node package*.json ./
COPY --chown=node:node . .

RUN yarn install --frozen-lockfile
RUN yarn build

USER node


FROM public.ecr.aws/amazonlinux/amazonlinux:2023

# 불필요한 권장 패키지를 제외하고 Node.js와 사용자 관리 도구를 설치합니다.
RUN dnf --setopt=install_weak_deps=False install -q -y \
    nodejs20 \
    shadow-utils \
    && dnf clean all

RUN alternatives --install /usr/bin/node node /usr/bin/node-20 90

ENV APPUSER=appuser
ENV APPUID=1000
ENV APPGID=1000

RUN useradd \
    --home "/app" \
    --create-home \
    --user-group \
    --uid "$APPUID" \
    "$APPUSER"

# [APM 추가] WhaTap이 작업 파일을 저장할 위치를 지정합니다.
ENV WHATAP_HOME=/whatap

# [APM 추가] 폴더를 만들고 앱 실행 사용자에게 소유권을 줍니다.
RUN mkdir -p /whatap && chown "$APPUID:$APPGID" /whatap

WORKDIR /app
USER appuser

COPY --chown=appuser:appuser --from=build /usr/src/app/node_modules ./node_modules
COPY --chown=appuser:appuser --from=build /usr/src/app/dist ./dist

# [APM 추가] WhaTap에 포함된 Linux 중계 프로그램에 실행 권한을 줍니다.
RUN chmod 0555 /app/node_modules/whatap/agent/linux/*/whatap_nodejs

ENTRYPOINT [ "node", "dist/main.js" ]