FROM eclipse-temurin:21.0.3_9-jre-alpine@sha256:f28691b4af71a91e60320257eae571c636151b89a85f222c9ba6a9685fea587f

WORKDIR /checkout-step/
COPY build/libs/checkout-step-all.jar /checkout-step/
CMD ["java", "-jar", "-XX:MaxRAMPercentage=85", "/checkout-step/checkout-step-all.jar"]