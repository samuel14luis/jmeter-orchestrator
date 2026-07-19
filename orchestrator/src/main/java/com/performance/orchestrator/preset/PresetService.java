package com.performance.orchestrator.preset;

import java.util.List;
import java.util.Map;

import com.performance.orchestrator.common.ApiException;
import com.performance.orchestrator.domain.Preset;
import com.performance.orchestrator.domain.Script;
import com.performance.orchestrator.domain.TestType;

import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class PresetService {

    public List<Preset> list() {
        return Preset.listAll(Sort.by("name"));
    }

    public Preset get(Long id) {
        Preset p = Preset.findById(id);
        if (p == null) {
            throw ApiException.notFound("Preset no encontrado: " + id);
        }
        return p;
    }

    @Transactional
    public Preset create(String name, Long scriptId, TestType type, Map<String, Object> parameters,
                         String targetEnv, String user) {
        if (scriptId == null) {
            throw ApiException.badRequest("scriptId es obligatorio");
        }
        if (Script.findById(scriptId) == null) {
            throw ApiException.badRequest("El script " + scriptId + " no existe");
        }
        Preset p = new Preset();
        p.name = name;
        p.scriptId = scriptId;
        p.testType = type;
        p.parameters = parameters != null ? parameters : new java.util.LinkedHashMap<>();
        p.targetEnv = targetEnv;
        p.createdBy = user;
        p.persist();
        return p;
    }

    @Transactional
    public Preset update(Long id, String name, TestType type, Map<String, Object> parameters, String targetEnv) {
        Preset p = get(id);
        if (name != null) {
            p.name = name;
        }
        if (type != null) {
            p.testType = type;
        }
        if (parameters != null) {
            p.parameters = parameters;
        }
        if (targetEnv != null) {
            p.targetEnv = targetEnv;
        }
        return p;
    }

    @Transactional
    public void delete(Long id) {
        if (!Preset.deleteById(id)) {
            throw ApiException.notFound("Preset no encontrado: " + id);
        }
    }
}
