# One image for main, tech, and the proxy. The lock decides the server jar and the third-party plugins.
# Our Kotlin plugins are compiled here and copied in by PLUGIN_PREFIX (backend- or velocity-).
#
# FROM-substitution ARGs must be declared before the first FROM (BuildKit global scope).
ARG RUNTIME_JAVA=21

FROM eclipse-temurin:21-jdk AS plugins

USER root
WORKDIR /src

COPY plugins ./plugins

RUN set -eux; \
    find plugins -name gradlew -exec chmod +x {} \; ; \
    find plugins -name gradlew -printf '%h\n' | while read -r project; do \
      (cd "$project" && ./gradlew shadowJar --no-daemon || ./gradlew build --no-daemon); \
    done; \
    mkdir -p /out; \
    find plugins -path '*/build/libs/*.jar' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      ! -name '*-plain.jar' \
      ! -name 'original-*' \
      -exec cp {} /out/ \;

FROM node:24-bookworm AS node

FROM eclipse-temurin:21-jdk AS fetch

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

# Jar reuse is owned by tools/plugin-lock (.plugin-lock-cache, keyed by content hash).
# CI seeds that directory via actions/cache + host prefetch, then passes it as the
# `plugin-cache` build context. Local compose uses .plugin-lock-cache or docker/empty-plugin-cache.
# Do not rely on BuildKit layer cache for jar bytes — cancelled/failed image builds must not wipe them.
COPY --from=plugin-cache . /plugin-lock-cache/

# github_token: fine-grained PAT for private plugin releases (optional in local builds).
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

# Runtime JRE only — main/proxy stay on 21; Paper 26.3 (tech) needs 25+.
# Plugin compile + fetch stay on 21 (bytecode runs fine on a newer JRE).
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

RUN sed -i 's/\r$//' /opt/misplease/entrypoint.sh && chmod +x /opt/misplease/entrypoint.sh

WORKDIR /data
USER root
ENTRYPOINT ["/opt/misplease/entrypoint.sh"]
