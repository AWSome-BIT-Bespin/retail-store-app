# Build Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023 AS build-env

# Go와 SQLite의 C 코드 빌드에 필요한 도구를 설치합니다.
RUN dnf --setopt=install_weak_deps=False install -q -y \
    git \
    golang \
    gcc \
    glibc-devel \
    && dnf clean all

# Go 의존성과 설치 도구의 저장 위치를 명확히 지정합니다.
ENV GOPATH=/go
ENV GOBIN=/usr/local/bin

# go.mod에서 더 높은 Go 버전을 요구하면 필요한 도구를 내려받습니다.
ENV GOTOOLCHAIN=auto
ENV GOPROXY=https://proxy.golang.org,direct

# catalog의 SQLite 드라이버가 사용하는 C 코드 연동을 활성화합니다.
ENV CGO_ENABLED=1

# 컨테이너 안에서 소스를 빌드할 위치입니다.
WORKDIR /appsrc

# 의존성 정보를 먼저 복사하고 다운로드합니다.
COPY go.mod go.sum ./
RUN go mod download

# 자동 계측 도구를 확인한 버전으로 고정해 설치합니다.
RUN go install github.com/whatap/go-api-inst/cmd/whatap-go-inst@v0.6.1

# catalog 소스 전체를 빌드 위치로 복사합니다.
COPY . .

# 현재 Go 패키지에 WhaTap 계측을 적용해 실행 파일을 만듭니다.
# 실행 가능한 결과물이 없으면 이미지 빌드를 실패시킵니다.
RUN /usr/local/bin/whatap-go-inst go build -o /appsrc/main . \
    && test -x /appsrc/main

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