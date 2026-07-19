package com.performance.orchestrator.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Almacen sobre directorio local / PVC RWX. Activo cuando
 * orchestrator.storage.kind=local (valor por defecto).
 */
@ApplicationScoped
@LookupIfProperty(name = "orchestrator.storage.kind", stringValue = "local", lookupIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path baseDir;

    public LocalStorageService(
            @ConfigProperty(name = "orchestrator.storage.local.base-dir", defaultValue = "./data/artifacts") String baseDir) {
        this.baseDir = Paths.get(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public String store(String relativePath, byte[] content) {
        Path target = resolve(relativePath);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo almacenar " + relativePath, e);
        }
        return relativePath;
    }

    @Override
    public byte[] read(String relativePath) {
        try {
            return Files.readAllBytes(resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + relativePath, e);
        }
    }

    @Override
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    @Override
    public Path resolve(String relativePath) {
        Path resolved = baseDir.resolve(relativePath).normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new IllegalArgumentException("Ruta fuera del almacen: " + relativePath);
        }
        return resolved;
    }
}
