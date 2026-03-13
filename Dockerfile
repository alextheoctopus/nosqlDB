# build stage
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle clean installDist --no-daemon

# run stage
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/nosql /app
EXPOSE 8080
CMD ["/app/bin/nosql"]
