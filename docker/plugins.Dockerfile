FROM eclipse-temurin:25-jdk

WORKDIR /src/plugins

COPY plugins/ ./

RUN set -eux; \
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
