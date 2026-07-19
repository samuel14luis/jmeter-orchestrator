package com.performance.orchestrator.scripts;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.w3c.dom.Document;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Validacion estatica de un .jmx (seccion 8.1 del plan):
 * XML bien formado, raiz jmeterTestPlan, tope de tamano y advertencia de
 * credenciales embebidas (seccion 10).
 */
@ApplicationScoped
public class ScriptValidator {

    // Heuristica simple: password="algo" no vacio dentro del XML.
    private static final Pattern EMBEDDED_PASSWORD =
            Pattern.compile("name=\"[^\"]*[Pp]assword[^\"]*\"\\s*>\\s*\\S", Pattern.DOTALL);

    @ConfigProperty(name = "orchestrator.scripts.max-size-bytes", defaultValue = "20971520")
    long maxSizeBytes;

    public ScriptValidationResult validate(byte[] content) {
        ScriptValidationResult result = ScriptValidationResult.ok();

        if (content == null || content.length == 0) {
            return result.error("El archivo esta vacio");
        }
        if (content.length > maxSizeBytes) {
            result.error("El archivo supera el tamano maximo permitido (" + maxSizeBytes + " bytes)");
        }

        Document doc;
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // Endurecer el parser frente a XXE (contexto bancario, seccion 10)
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            doc = builder.parse(new ByteArrayInputStream(content));
        } catch (Exception e) {
            return result.error("XML mal formado: " + e.getMessage());
        }

        String root = doc.getDocumentElement().getNodeName();
        if (!"jmeterTestPlan".equals(root)) {
            result.error("La raiz del documento debe ser <jmeterTestPlan>, encontrado <" + root + ">");
        }

        String text = new String(content, StandardCharsets.UTF_8);
        if (EMBEDDED_PASSWORD.matcher(text).find()) {
            result.warn("Se detectaron posibles credenciales embebidas; use Secrets de K8s / Key Vault en lugar de valores en el .jmx");
        }
        if (!text.contains("${__P(")) {
            result.warn("El script no usa la convencion ${__P(...)}; el orquestador no podra controlar hilos/rampa/duracion via -J");
        }

        return result;
    }
}
