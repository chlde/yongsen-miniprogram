# syntax=docker/dockerfile:1
# The same immutable image serves the SCF Java API and the CloudBase Node gateway.
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
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates ffmpeg poppler-utils fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 training \
    && useradd --uid 10001 --gid training --no-create-home --shell /usr/sbin/nologin training \
    && mkdir -p /opt/yongsen \
    && chown training:training /opt/yongsen
WORKDIR /opt/yongsen
COPY --from=backend-build --chown=training:training /build/backend/target/training-backend-1.0.0.jar ./app.jar
COPY --from=web-build /usr/local/bin/node /usr/local/bin/node
COPY --from=web-build --chown=training:training /build/web/dist/ ./web/
COPY --chown=training:training deployment/serverless/cloudbase-gateway.cjs ./cloudbase-gateway.cjs
COPY --chown=training:training --chmod=0555 deployment/serverless/scf_bootstrap ./scf_bootstrap
ENV SPRING_PROFILES_ACTIVE=serverless \
    WECHAT_MODE=real \
    TRAINING_STORAGE_MODE=cos \
    TRAINING_BACKGROUND_MODE=scf \
    FFPROBE_PATH=/usr/bin/ffprobe \
    PDFINFO_PATH=/usr/bin/pdfinfo \
    PDFTOPPM_PATH=/usr/bin/pdftoppm \
    TZ=Asia/Shanghai
USER 10001:10001
EXPOSE 9000
ENTRYPOINT ["/opt/yongsen/scf_bootstrap"]
