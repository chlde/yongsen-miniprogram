# syntax=docker/dockerfile:1
# Build from the repository root. CloudBase currently requires linux/amd64.
FROM node:22-bookworm-slim AS web-build
WORKDIR /build/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
ENV VITE_API_BASE_URL=/api/v1
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build/backend
COPY backend/pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY backend/src/main/ ./src/main/
COPY --from=web-build /build/web/dist/ ./src/main/resources/static/
# Run the project's complete verification separately before building a release.
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates curl ffmpeg poppler-utils fonts-noto-cjk tini util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 training \
    && useradd --uid 10001 --gid training --no-create-home --shell /usr/sbin/nologin training \
    && mkdir -p /opt/yongsen /mnt/training \
    && chown training:training /opt/yongsen /mnt/training
WORKDIR /opt/yongsen
COPY --from=backend-build --chown=training:training /build/backend/target/training-backend-1.0.0.jar ./app.jar
COPY --chown=training:training --chmod=0555 deployment/entrypoint.sh ./entrypoint.sh
ENV SPRING_PROFILES_ACTIVE=prod \
    SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080 \
    TRAINING_DATA_DIR=/mnt/training \
    TRAINING_MAX_UPLOAD_BYTES=18874368 \
    FFPROBE_PATH=/usr/bin/ffprobe \
    PDFINFO_PATH=/usr/bin/pdfinfo \
    PDFTOPPM_PATH=/usr/bin/pdftoppm \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60.0 -Djava.awt.headless=true -Dfile.encoding=UTF-8"
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --show-error "http://127.0.0.1:${PORT:-${SERVER_PORT:-8080}}/health" > /dev/null || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/opt/yongsen/entrypoint.sh"]
