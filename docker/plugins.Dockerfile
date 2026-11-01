FROM eclipse-temurin:21-jdk-jammy

WORKDIR /src

COPY plugins ./plugins

RUN set -eux; \
    find plugins -name gradlew -exec chmod +x {} \; ; \
    find plugins -name gradlew -printf '%h\n' | while read -r project; do \
      (cd "$project" && ./gradlew build --no-daemon); \
    done; \
    mkdir -p /out; \
    find plugins -path '*/build/libs/*.jar' \
      ! -name '*-sources.jar' \
      ! -name '*-javadoc.jar' \
      -exec cp {} /out/ \;

CMD ["ls", "-la", "/out"]
