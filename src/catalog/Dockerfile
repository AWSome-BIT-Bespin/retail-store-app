# Build Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023 AS build-env

RUN dnf --setopt=install_weak_deps=False install -q -y \
    git \
    golang \
    && dnf clean all

RUN mkdir -p "${GOPATH}/src" "${GOPATH}/bin" /appsrc

WORKDIR /appsrc

ENV GOPROXY=https://proxy.golang.org,direct

COPY go.mod go.sum ./
RUN go mod download

COPY . .

# WhaTap Go 자동 계측 도구 설치 후, 계측된 바이너리를 빌드합니다.
RUN go install github.com/whatap/go-api-inst/cmd/whatap-go-inst@latest
RUN "$(go env GOPATH)/bin/whatap-go-inst" go build -o main main.go

# Final Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023

ENV APPUSER=appuser
ENV APPUID=1000
ENV APPGID=1000

# Amazon Linux용 WhaTap data relay agent를 설치합니다.
RUN rpm --import https://repo.whatap.io/centos/release.gpg \
    && { \
      echo '[whatap]'; \
      echo 'name=whatap packages for enterprise linux'; \
      echo 'baseurl=https://repo.whatap.io/centos/latest/$basearch'; \
      echo 'enabled=1'; \
      echo 'gpgcheck=0'; \
    } > /etc/yum.repos.d/whatap.repo \
    && dnf --setopt=install_weak_deps=False install -q -y \
      shadow-utils \
      whatap-agent \
    && dnf clean all

RUN useradd \
    --home "/app" \
    --create-home \
    --user-group \
    --uid "$APPUID" \
    "$APPUSER"

WORKDIR /app

COPY --chown=appuser:appuser --from=build-env /appsrc/main /app/
COPY --chown=appuser:appuser ./ATTRIBUTION.md ./LICENSES.md
COPY --chown=appuser:appuser entrypoint.sh /app/entrypoint.sh

RUN chmod 0555 /app/entrypoint.sh

USER appuser

ENV GIN_MODE=release

ENTRYPOINT ["/app/entrypoint.sh"]