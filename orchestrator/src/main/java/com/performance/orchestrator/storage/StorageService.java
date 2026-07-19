package com.performance.orchestrator.storage;

import java.nio.file.Path;

/**
 * Abstraccion del almacen de artefactos (.jmx, .jtl, reportes, logs).
 * v1: implementacion sobre PVC RWX / directorio local montado.
 * Alternativa futura: Azure Blob Storage (decision abierta, seccion 13).
 */
public interface StorageService {

    /** Guarda contenido en la ruta relativa indicada y devuelve la ruta logica almacenada. */
    String store(String relativePath, byte[] content);

    /** Lee el contenido de una ruta logica. */
    byte[] read(String relativePath);

    /** Indica si existe el artefacto. */
    boolean exists(String relativePath);

    /** Resuelve la ruta a un Path fisico local (para montaje compartido con los workers). */
    Path resolve(String relativePath);
}
