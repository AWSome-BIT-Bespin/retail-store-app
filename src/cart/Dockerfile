# Build Stage: Maven과 JDK로 애플리케이션 JAR을 만드는 단계입니다.
# Multi-stage build는 이 빌드 도구를 Final Stage에 넣지 않고 결과 JAR만 넘겨 Runtime Image를 분리합니다.
# Build Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023 as build-env

# Amazon Linux 2023 Base Image는 이 단계에서 dnf로 빌드 도구를 설치할 수 있는 운영체제 기반을 제공합니다.
# We tell DNF not to install Recommends and Suggests packages, keeping our
# installed set of packages as minimal as possible.
RUN dnf --setopt=install_weak_deps=False install -q -y \
    maven \
    java-21-amazon-corretto-headless \
    which \
    tar \
    gzip \
    && \
    dnf clean all

# install_weak_deps=False는 패키지가 권장하는 약한 의존성을 자동 설치하지 않도록 합니다.
# dnf clean all은 같은 RUN Layer에 남는 패키지 메타데이터 캐시를 정리합니다.
# ARG는 이미지에 남지 않는 빌드 시점 변수입니다. JAR_PATH는 현재 명령에서 참조하지 않습니다.
ARG JAR_PATH

VOLUME /tmp
# WORKDIR /는 이후 Maven Wrapper와 pom.xml을 복사·실행할 기준 경로를 고정합니다.
WORKDIR /

# Maven Wrapper 파일을 먼저 복사해 프로젝트가 지정한 Maven 버전으로 빌드합니다.
# 소스보다 먼저 분리하면 소스만 바뀐 빌드에서 의존성 Layer Cache를 재사용할 수 있습니다.
COPY .mvn .mvn
COPY mvnw .
# pom.xml은 의존성 선언이므로 소스보다 먼저 복사해 의존성 다운로드 Layer를 분리합니다.
COPY pom.xml .

# 필요한 라이브러리와 Maven 플러그인을 소스 컴파일 전에 내려받아 이후 패키징에 사용합니다.
RUN ./mvnw dependency:go-offline -B -q

# 의존성 Layer를 만든 뒤에 애플리케이션 소스를 복사합니다.
COPY ./src ./src

# -DskipTests는 이미지 빌드 중 테스트 실행을 생략하고 패키징합니다.
# 테스트를 생략하더라도 검증은 CI에서 별도로 실행해야 합니다.
RUN ./mvnw -DskipTests package -q && \
    mv /target/carts-0.0.1-SNAPSHOT.jar /app.jar

# Final/Runtime Stage: 실행에 필요한 JRE와 생성된 JAR만으로 컨테이너를 구성합니다.
# Package Stage
FROM public.ecr.aws/amazonlinux/amazonlinux:2023

# Runtime Base Image도 Amazon Linux 2023으로 두어 dnf로 필요한 런타임 패키지를 설치합니다.
# We tell DNF not to install Recommends and Suggests packages, which are
# weak dependencies in DNF terminology, thus keeping our installed set of
# packages as minimal as possible.
RUN dnf --setopt=install_weak_deps=False install -q -y \
    java-21-amazon-corretto-headless \
    shadow-utils \
    && \
    dnf clean all

# install_weak_deps=False와 dnf clean all은 Build Stage와 같은 이유로 불필요한 약한 의존성과 캐시를 남기지 않습니다.
ENV APPUSER=appuser
ENV APPUID=1000
ENV APPGID=1000

# ENV는 이미지 실행 시 사용할 환경 변수의 기본값을 설정합니다.
# APPUID=1000은 useradd의 --uid에 적용됩니다. APPGID도 1000으로 선언됐지만, 아래 useradd 명령은 GID를 직접 지정하지 않습니다.
# 고정 UID/GID는 볼륨을 마운트하는 환경에서 사용자 식별자를 예측 가능하게 맞추려는 용도로 사용할 수 있습니다.
# root가 아닌 전용 계정으로 실행해 애플리케이션 프로세스의 권한 범위를 줄입니다.
RUN useradd \
    --home "/app" \
    --create-home \
    --user-group \
    --uid "$APPUID" \
    "$APPUSER"

# JAVA_TOOL_OPTIONS는 JVM이 자동으로 읽는 추가 옵션용 환경 변수이며, 비워 두어 실행 환경에서 설정할 수 있습니다.
# SPRING_PROFILES_ACTIVE=prod는 Spring Boot가 prod 프로필 설정을 선택하도록 합니다.
ENV JAVA_TOOL_OPTIONS=
ENV SPRING_PROFILES_ACTIVE=prod

# WORKDIR /app은 이후 복사와 실행의 기준 경로입니다.
WORKDIR /app
USER appuser

COPY ./ATTRIBUTION.md ./LICENSES.md
# Build Stage의 JAR만 가져와 Maven과 JDK가 포함된 빌드 환경을 Runtime Image에 넣지 않습니다.
# --chown은 복사 시점에 appuser 소유로 만들어 비루트 사용자도 읽고 실행할 수 있게 합니다.
COPY --chown=appuser:appuser --from=build-env /app.jar .

# EXPOSE 8080은 컨테이너가 사용하는 포트를 문서화합니다. 포트를 외부에 공개하는 설정 자체는 아닙니다.
EXPOSE 8080

# Secret·Credential·Token은 Image Layer와 이력에 남을 수 있으므로 Dockerfile에 넣지 않고 실행 환경의 Secret 주입 방식을 사용합니다.
# sh -c가 실행 시 JAVA_OPTS를 확장한 뒤, java가 /app/app.jar를 실행합니다.
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
