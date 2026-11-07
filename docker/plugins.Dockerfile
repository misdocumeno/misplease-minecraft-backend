FROM eclipse-temurin:25-jdk

WORKDIR /src/plugins

COPY plugins/ ./

RUN --mount=type=cache,target=/root/.gradle/caches,id=misplease-gradle-caches \
    --mount=type=cache,target=/root/.gradle/wrapper,id=misplease-gradle-wrapper \
    set -eux; \
    chmod +x gradlew; \
    ./gradlew --no-daemon --parallel shadowJars; \
    mkdir -p /out; \
    find . -path '*/build/libs/*.jar' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      ! -name '*-plain.jar' \
      ! -name 'original-*' \
      -exec cp {} /out/ \;

CMD ["ls", "-la", "/out"]
