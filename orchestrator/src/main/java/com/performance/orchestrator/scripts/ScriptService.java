package com.performance.orchestrator.scripts;

import java.util.List;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.common.Checksums;
import com.performance.orchestrator.domain.AuditEvent;
import com.performance.orchestrator.domain.Script;
import com.performance.orchestrator.domain.ScriptVersion;
import com.performance.orchestrator.storage.StorageService;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class ScriptService {

    @Inject
    StorageService storage;

    @Inject
    ScriptValidator validator;

    public List<Script> listScripts() {
        return Script.listAll(Sort.by("createdAt").descending());
    }

    public Script getScript(Long id) {
        Script s = Script.findById(id);
        if (s == null) {
            throw ApiException.notFound("Script no encontrado: " + id);
        }
        return s;
    }

    public List<ScriptVersion> listVersions(Long scriptId) {
        getScript(scriptId);
        return ScriptVersion.list("scriptId = ?1 order by versionNumber desc", scriptId);
    }

    public ScriptVersion getVersion(Long scriptId, int versionNumber) {
        ScriptVersion v = ScriptVersion.findByScriptAndNumber(scriptId, versionNumber);
        if (v == null) {
            throw ApiException.notFound("Version " + versionNumber + " no encontrada para script " + scriptId);
        }
        return v;
    }

    public byte[] getVersionContent(Long scriptId, int versionNumber) {
        ScriptVersion v = getVersion(scriptId, versionNumber);
        return storage.read(v.blobPath);
    }

    public ScriptValidationResult validate(byte[] content) {
        return validator.validate(content);
    }

    /** Sube un .jmx nuevo: crea Script + version 1. Rechaza si la validacion falla. */
    @Transactional
    public ScriptVersion createScript(String name, String description, String tags, String user, byte[] content) {
        ScriptValidationResult validation = validator.validate(content);
        if (!validation.valid) {
            throw ApiException.badRequest("Script invalido: " + String.join("; ", validation.errors));
        }
        if (Script.count("name", name) > 0) {
            throw ApiException.conflict("Ya existe un script con el nombre '" + name + "'");
        }

        Script script = new Script();
        script.name = name;
        script.description = description;
        script.tags = tags;
        script.createdBy = user;
        script.persist();

        ScriptVersion version = persistVersion(script.id, 1, content, "Version inicial", user);
        AuditEvent.record(user, "UPLOAD", "Script", script.id, "name=" + name);
        return version;
    }

    /** Guarda una edicion como nueva version inmutable. */
    @Transactional
    public ScriptVersion saveNewVersion(Long scriptId, byte[] content, String notes, String user) {
        getScript(scriptId);
        ScriptValidationResult validation = validator.validate(content);
        if (!validation.valid) {
            throw ApiException.badRequest("Script invalido: " + String.join("; ", validation.errors));
        }
        int next = nextVersionNumber(scriptId);
        ScriptVersion version = persistVersion(scriptId, next, content, notes, user);
        AuditEvent.record(user, "EDIT", "Script", scriptId, "version=" + next);
        return version;
    }

    private int nextVersionNumber(Long scriptId) {
        ScriptVersion last = ScriptVersion.find("scriptId = ?1 order by versionNumber desc", scriptId).firstResult();
        return last == null ? 1 : last.versionNumber + 1;
    }

    private ScriptVersion persistVersion(Long scriptId, int number, byte[] content, String notes, String user) {
        String checksum = Checksums.sha256Hex(content);
        String blobPath = "scripts/" + scriptId + "/v" + number + ".jmx";
        storage.store(blobPath, content);

        ScriptVersion version = new ScriptVersion();
        version.scriptId = scriptId;
        version.versionNumber = number;
        version.blobPath = blobPath;
        version.checksumSha256 = checksum;
        version.notes = notes;
        version.createdBy = user;
        version.persist();
        return version;
    }
}
