# =====================================================================
#  Imagen worker JMeter 5.6.x (Fase 0 del plan)
#  Incluye Plugins Manager + plugins comunes (Ultimate Thread Group, etc.)
# =====================================================================
FROM eclipse-temurin:21-jre-jammy

ARG JMETER_VERSION=5.6.3
ARG PLUGINS_MANAGER_VERSION=1.10
ARG CMDRUNNER_VERSION=2.3

ENV JMETER_HOME=/opt/apache-jmeter-${JMETER_VERSION}
ENV PATH=${JMETER_HOME}/bin:${PATH}

RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends curl unzip bash ca-certificates jq gzip coreutils; \
    rm -rf /var/lib/apt/lists/*

# JMeter core
RUN set -eux; \
    curl -fsSL "https://archive.apache.org/dist/jmeter/binaries/apache-jmeter-${JMETER_VERSION}.tgz" \
      -o /tmp/jmeter.tgz; \
    tar -xzf /tmp/jmeter.tgz -C /opt; \
    rm /tmp/jmeter.tgz

# Plugins Manager + cmdrunner + plugins comunes
RUN set -eux; \
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/jmeter-plugins-manager/${PLUGINS_MANAGER_VERSION}/jmeter-plugins-manager-${PLUGINS_MANAGER_VERSION}.jar" \
      -o "${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar"; \
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/cmdrunner/${CMDRUNNER_VERSION}/cmdrunner-${CMDRUNNER_VERSION}.jar" \
      -o "${JMETER_HOME}/lib/cmdrunner.jar"; \
    java -cp "${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar" \
      org.jmeterplugins.repository.PluginManagerCMDInstaller; \
    "${JMETER_HOME}/bin/PluginsManagerCMD.sh" install \
      jpgc-casutg jpgc-tst jpgc-dummy jpgc-functions || true

# Ajuste de memoria por defecto (se recomienda mas pods con menos hilos, seccion 12)
ENV JVM_ARGS="-Xms512m -Xmx1536m"

# Dispatcher (run.sh) + agente pull (agent.sh, Fase 7) + entrypoint legado de Jobs.
# El dispatcher usa el agente si ORCHESTRATOR_URL esta definido; si no, el modo Jobs.
COPY entrypoint.sh /entrypoint.sh
COPY agent.sh /agent.sh
COPY run.sh /run.sh
RUN chmod +x /entrypoint.sh /agent.sh /run.sh

ENTRYPOINT ["/run.sh"]
