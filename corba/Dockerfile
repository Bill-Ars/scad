# ---------- Etapa 1: compilar a IDL e o codigo Java (JDK 8, alvo do JacORB 3.9) ----------
FROM maven:3.9-eclipse-temurin-8 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY idl ./idl
# Compilador IDL do JacORB: gera stubs, skeletons (POA), helpers e holders a partir de scad.idl
RUN mvn -q dependency:build-classpath -Dmdep.outputFile=cp.txt \
 && mkdir -p target/generated-sources/idl \
 && java -cp "$(cat cp.txt)" org.jacorb.idl.parser -d target/generated-sources/idl idl/scad.idl \
 && find target/generated-sources/idl -name "*.java"
COPY src ./src
RUN mvn -q -DskipTests package

# ---------- Etapa 2: executar ----------
FROM eclipse-temurin:8-jre
WORKDIR /app
COPY --from=build /build/target/corba-1.0.jar app.jar
COPY --from=build /build/target/lib ./lib
COPY run.sh /app/run.sh
ENTRYPOINT ["sh", "/app/run.sh"]
