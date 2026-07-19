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

# Plugins Manager + cmdrunner + plugins comunes.
#  · jpgc-tst    = Throughput Shaping Timer (rampa por RPS objetivo, Fase RPS)
#  · jpgc-casutg = Custom Thread Groups (Stepping/Concurrency/Ultimate)
# La lista de 'install' va SEPARADA POR COMAS (con espacios PluginsManagerCMD no
# instala nada). Sin '|| true': si la descarga falla, el build DEBE fallar; y
# verificamos que el jar del TST realmente quedó en lib/ext.
RUN set -eux; \
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/jmeter-plugins-manager/${PLUGINS_MANAGER_VERSION}/jmeter-plugins-manager-${PLUGINS_MANAGER_VERSION}.jar" \
      -o "${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar"; \
    curl -fsSL "https://repo1.maven.org/maven2/kg/apc/cmdrunner/${CMDRUNNER_VERSION}/cmdrunner-${CMDRUNNER_VERSION}.jar" \
      -o "${JMETER_HOME}/lib/cmdrunner-${CMDRUNNER_VERSION}.jar"; \
    java -cp "${JMETER_HOME}/lib/ext/jmeter-plugins-manager.jar" \
      org.jmeterplugins.repository.PluginManagerCMDInstaller; \
    "${JMETER_HOME}/bin/PluginsManagerCMD.sh" install jpgc-casutg,jpgc-tst,jpgc-dummy,jpgc-functions; \
    ls "${JMETER_HOME}/lib/ext/" | grep -Eiq 'tst|ThroughputShaping' \
      || (echo "ERROR: el plugin Throughput Shaping Timer (jpgc-tst) no se instalo" && exit 1)

# Ajuste de memoria por defecto (se recomienda mas pods con menos hilos, seccion 12)
ENV JVM_ARGS="-Xms512m -Xmx1536m"

# Agente pull del worker-pool (Fase 7): reclama shards, ejecuta jmeter -n y sube
# resultados por HTTP. Requiere ORCHESTRATOR_URL y WORKER_TOKEN (ver deploy/pool/).
COPY agent.sh /agent.sh
RUN chmod +x /agent.sh

ENTRYPOINT ["/agent.sh"]
