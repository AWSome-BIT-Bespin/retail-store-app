# Build Stage: Go 소스와 C 연동 의존성을 컴파일해 실행 파일을 만드는 단계입니다.
# Multi-stage build는 Go 컴파일 도구를 Final Stage에 넣지 않고 결과 Binary만 넘겨 Runtime Image를 분리합니다.
# Build Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023 AS build-env

# Amazon Linux 2023 Base Image는 최종 실행 환경과 같은 계열에서 Go 도구와 시스템 헤더를 설치할 기반을 제공합니다.
# Go와 SQLite의 C 코드 빌드에 필요한 도구를 설치합니다.
RUN dnf --setopt=install_weak_deps=False install -q -y \
    git \
    golang \
    gcc \
    glibc-devel \
    && dnf clean all

# install_weak_deps=False는 패키지가 권장하는 약한 의존성을 자동 설치하지 않도록 합니다.
# gcc와 glibc-devel은 SQLite 드라이버의 C 코드 연동을 컴파일하는 데 필요합니다.
# dnf clean all은 같은 RUN Layer에 남는 패키지 메타데이터 캐시를 정리합니다.
# Go 의존성과 설치 도구의 저장 위치를 명확히 지정합니다.
ENV GOPATH=/go
ENV GOBIN=/usr/local/bin

# go.mod에서 더 높은 Go 버전을 요구하면 필요한 도구를 내려받습니다.
# ENV는 이 Stage의 Go 도구 동작에 사용할 환경 변수입니다.
# GOTOOLCHAIN=auto는 go.mod가 더 높은 Go 버전을 요구할 때 필요한 Toolchain을 자동으로 사용하게 합니다.
ENV GOTOOLCHAIN=auto
# GOPROXY는 모듈을 먼저 Go Proxy에서 받고, 받을 수 없을 때 직접 소스를 조회하는 순서를 정합니다.
ENV GOPROXY=https://proxy.golang.org,direct

# catalog의 SQLite 드라이버가 사용하는 C 코드 연동을 활성화합니다.
# CGO_ENABLED=1은 이 C 코드 연동을 활성화합니다.
ENV CGO_ENABLED=1

# 컨테이너 안에서 소스를 빌드할 위치입니다.
# WORKDIR /appsrc는 이후 복사와 Go 빌드 명령의 기준 경로입니다.
WORKDIR /appsrc

# 의존성 정보를 먼저 복사하고 다운로드합니다.
# go.mod와 go.sum을 소스보다 먼저 복사해 모듈 다운로드 Layer를 분리합니다.
# 애플리케이션 소스만 바뀐 빌드에서는 이 Layer Cache를 재사용할 수 있습니다.
COPY go.mod go.sum ./
RUN go mod download

# 자동 계측 도구를 확인한 버전으로 고정해 설치합니다.
# whatap-go-inst는 go build 명령을 감싸 WhaTap 계측이 적용된 Binary를 만들기 위해 Build Stage에만 설치합니다.
# 버전을 v0.6.1로 고정해 이미지 빌드마다 도구 버전이 임의로 바뀌지 않게 합니다.
RUN go install github.com/whatap/go-api-inst/cmd/whatap-go-inst@v0.6.1

# catalog 소스 전체를 빌드 위치로 복사합니다.
# 의존성 Layer를 만든 뒤 복사해 소스 변경이 모듈 다운로드 Layer를 무효화하지 않게 합니다.
COPY . .

# 현재 Go 패키지에 WhaTap 계측을 적용해 실행 파일을 만듭니다.
# 실행 가능한 결과물이 없으면 이미지 빌드를 실패시킵니다.
# test -x는 결과물이 존재하고 실행 권한이 있는지 확인해 조건을 만족하지 않으면 이미지 빌드를 실패시킵니다.
RUN /usr/local/bin/whatap-go-inst go build -o /appsrc/main . \
    && test -x /appsrc/main

# Final/Runtime Stage: 컴파일 도구 없이 실행 파일과 런타임 Agent만으로 컨테이너를 구성합니다.
# Final Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023

# ENV는 이미지 실행 시 사용할 환경 변수의 기본값을 설정합니다.
ENV APPUSER=appuser
ENV APPUID=1000
ENV APPGID=1000

# Amazon Linux용 WhaTap data relay agent를 설치합니다.
# whatap-agent는 entrypoint.sh가 설정에 따라 실행하는 WhaTap data relay agent를 제공합니다.
# Agent 설정의 Secret·Credential·Token은 Image Layer와 이력에 남을 수 있으므로 Dockerfile에 넣지 않고 실행 환경에서 주입합니다.
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

# install_weak_deps=False는 약한 의존성의 자동 설치를 막고, dnf clean all은 패키지 메타데이터 캐시를 정리합니다.
# APPUID=1000은 useradd의 --uid에 적용됩니다. APPGID도 1000으로 선언됐지만, 아래 useradd 명령은 GID를 직접 지정하지 않습니다.
# root가 아닌 전용 계정으로 실행해 애플리케이션 프로세스의 권한 범위를 줄입니다.
RUN useradd \
    --home "/app" \
    --create-home \
    --user-group \
    --uid "$APPUID" \
    "$APPUSER"

# WORKDIR /app은 이후 복사와 ENTRYPOINT 실행의 기준 경로입니다.
WORKDIR /app

# Build Stage에서 생성한 Binary만 가져옵니다.
# --chown은 복사 시점에 appuser 소유로 만들어 비루트 사용자도 실행할 수 있게 합니다.
COPY --chown=appuser:appuser --from=build-env /appsrc/main /app/
COPY --chown=appuser:appuser ./ATTRIBUTION.md ./LICENSES.md
# entrypoint.sh는 WhaTap 설정 여부를 처리한 뒤 /app/main을 exec로 실행합니다.
COPY --chown=appuser:appuser entrypoint.sh /app/entrypoint.sh

# 0555는 모든 사용자에게 읽기·실행 권한만 주고 쓰기 권한은 주지 않습니다.
RUN chmod 0555 /app/entrypoint.sh

USER appuser

# GIN_MODE=release는 Gin 프레임워크를 release 모드로 실행하도록 전달합니다.
ENV GIN_MODE=release

# ENTRYPOINT는 셸 스크립트를 실행하며, 스크립트가 최종적으로 /app/main 프로세스로 교체됩니다.
ENTRYPOINT ["/app/entrypoint.sh"]
