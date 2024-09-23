FROM eclipse-temurin:21.0.3_9-jre-alpine@sha256:f28691b4af71a91e60320257eae571c636151b89a85f222c9ba6a9685fea587f

WORKDIR /push-step/
COPY build/libs/push-step-all.jar /push-step/
CMD ["java", "-jar", "-XX:MaxRAMPercentage=85", "/push-step/push-step-all.jar"]