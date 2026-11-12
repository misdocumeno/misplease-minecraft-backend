# One image for main, tech, and the proxy. The lock decides the server jar and the third-party plugins.
# Our Kotlin plugins are compiled here and copied in by PLUGIN_PREFIX (backend- or velocity-).
#
# Third-party jars: CI materializes them on the GHA runner (plugin-lock + @actions/cache per
# lock entry) and passes lock-jars / lock-server build contexts (LOCK_VARIANT=ci).
# Local compose keeps LOCK_VARIANT=local and downloads inside this image (disk cache only).
#
# FROM-substitution ARGs must be declared before the first FROM (BuildKit global scope).
ARG RUNTIME_JAVA=21
ARG LOCK_VARIANT=local
# backend- | velocity- — only compile matching projects (one Gradle daemon, filtered tasks).
ARG PLUGIN_PREFIX=backend-

# Paper 26.3 / Velocity 4.3 APIs require JDK 25+ to resolve (Gradle toolchain).
FROM eclipse-temurin:25-jdk AS plugins

ARG PLUGIN_PREFIX

USER root
WORKDIR /src/plugins

COPY plugins/ ./

# Cache mounts keep Gradle deps/wrapper warm when this RUN re-executes.
# BuildKit does NOT export mounts via cache-to type=gha — CI injects them with
# buildkit-cache-dance + actions/cache (see images.yml). Local builds reuse the
# builder's on-disk mounts automatically.
RUN --mount=type=cache,target=/root/.gradle/caches,id=misplease-gradle-caches \
    --mount=type=cache,target=/root/.gradle/wrapper,id=misplease-gradle-wrapper \
    set -eux; \
    chmod +x gradlew; \
    ./gradlew --no-daemon --parallel shadowJars -PpluginPrefix="${PLUGIN_PREFIX}"; \
    mkdir -p /out; \
    find . -path '*/build/libs/*.jar' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      ! -name '*-plain.jar' \
      ! -name 'original-*' \
      -exec cp {} /out/ \;

FROM node:24-bookworm AS node

# --- local: download lock jars inside the image (no Actions cache) ---
FROM eclipse-temurin:21-jdk AS fetch-local

COPY --from=node /usr/local/ /usr/local/

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends git ca-certificates; \
    rm -rf /var/lib/apt/lists/*

WORKDIR /src

COPY tools/plugin-lock/package.json tools/plugin-lock/package-lock.json tools/plugin-lock/
RUN cd tools/plugin-lock && npm ci --omit=dev --ignore-scripts

COPY tools/plugin-lock tools/plugin-lock

ARG LOCK
COPY ${LOCK} /lock.yml

# Optional local disk cache (.plugin-lock-cache/<entryHash>.jar).
COPY --from=plugin-cache . /plugin-lock-cache/

RUN --mount=type=secret,id=github_token \
    set -eux; \
    export PLUGIN_LOCK_CACHE=/plugin-lock-cache; \
    if [ -f /run/secrets/github_token ]; then \
      export GITHUB_TOKEN="$(cat /run/secrets/github_token)"; \
      export GH_TOKEN="$GITHUB_TOKEN"; \
    fi; \
    cd tools/plugin-lock; \
    node src/cli.ts download --lock /lock.yml --out /jars --server-out /server; \
    test -n "$(find /server -maxdepth 1 -name '*.jar' -print -quit)"; \
    test -n "$(find /jars -maxdepth 1 -name '*.jar' -print -quit)"

# --- CI: jars already materialized on the runner ---
FROM eclipse-temurin:21-jre AS fetch-ci

COPY --from=lock-jars . /jars
COPY --from=lock-server . /server

RUN set -eux; \
    test -n "$(find /server -maxdepth 1 -name '*.jar' -print -quit)"; \
    test -n "$(find /jars -maxdepth 1 -name '*.jar' -print -quit)"

FROM fetch-${LOCK_VARIANT} AS fetch

# Runtime JRE — Paper 26.3 and Velocity 4.3 need 25+.
FROM eclipse-temurin:${RUNTIME_JAVA}-jre

USER root

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends fuse-overlayfs ca-certificates; \
    rm -rf /var/lib/apt/lists/*; \
    if ! id -u 1000 >/dev/null 2>&1; then \
      groupadd --gid 1000 misplease; \
      useradd --uid 1000 --gid 1000 --no-create-home --shell /usr/sbin/nologin misplease; \
    fi

ARG PLUGIN_PREFIX
COPY --from=fetch /jars /opt/misplease/jars
COPY --from=fetch /server /opt/misplease/server
COPY --from=plugins /out /tmp/our-plugins

RUN set -eux; \
    find /tmp/our-plugins -name "${PLUGIN_PREFIX}*.jar" -exec cp {} /opt/misplease/jars/ \; ; \
    rm -rf /tmp/our-plugins; \
    test -n "$(find /opt/misplease/jars -name "${PLUGIN_PREFIX}*.jar" -print -quit)"

COPY docker/entrypoint.sh /opt/misplease/entrypoint.sh

RUN sed -i 's/\r$//' /opt/misplease/entrypoint.sh \
    && chmod +x /opt/misplease/entrypoint.sh

WORKDIR /data
USER root
ENTRYPOINT ["/opt/misplease/entrypoint.sh"]
