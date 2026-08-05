FROM gradle:9.4.1-jdk21 AS build
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN gradle --no-daemon :server:installDist

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache wget && addgroup -S oyla && adduser -S oyla -G oyla
WORKDIR /app
COPY --from=build --chown=oyla:oyla /workspace/server/build/install/server/ /app/
USER oyla
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
