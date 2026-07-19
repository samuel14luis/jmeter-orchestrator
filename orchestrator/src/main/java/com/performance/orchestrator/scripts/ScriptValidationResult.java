package com.performance.orchestrator.scripts;

import java.util.ArrayList;
import java.util.List;

/** Resultado de la validacion estatica de un .jmx. */
public class ScriptValidationResult {

    public boolean valid;
    public final List<String> errors = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public static ScriptValidationResult ok() {
        ScriptValidationResult r = new ScriptValidationResult();
        r.valid = true;
        return r;
    }

    public ScriptValidationResult error(String msg) {
        this.valid = false;
        this.errors.add(msg);
        return this;
    }

    public ScriptValidationResult warn(String msg) {
        this.warnings.add(msg);
        return this;
    }
}
