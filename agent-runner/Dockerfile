FROM amazoncorretto:17-alpine AS build

RUN apk add --no-cache bash curl git

WORKDIR /build/agent-runner
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY src src

RUN chmod +x mvnw
RUN ./mvnw -B -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

FROM amazoncorretto:17-alpine

RUN apk add --no-cache bash curl git

WORKDIR /workspace/agent-runner

COPY --from=build /build/agent-runner/target/classes /opt/agent-runner/classes
COPY --from=build /build/agent-runner/target/dependency /opt/agent-runner/dependency

ENV AGENT_MAIN_CLASS=com.example.agent.JavaOrchestratorAgent

ENTRYPOINT ["sh", "-c", "java -cp '/opt/agent-runner/classes:/opt/agent-runner/dependency/*' ${AGENT_MAIN_CLASS} \"$@\"", "--"]
