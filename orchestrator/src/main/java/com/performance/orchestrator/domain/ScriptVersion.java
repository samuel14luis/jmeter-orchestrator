package com.performance.orchestrator.domain;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Version inmutable de un script: cada guardado crea una nueva. */
@Entity
@Table(name = "script_version",
        indexes = @Index(name = "UX_scriptversion_script_number", columnList = "script_id,version_number", unique = true))
public class ScriptVersion extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "script_id", nullable = false)
    public Long scriptId;

    @Column(name = "version_number", nullable = false)
    public Integer versionNumber;

    /** Ruta del .jmx en el almacen de artefactos. */
    @Column(name = "blob_path", nullable = false)
    public String blobPath;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    public String checksumSha256;

    @Column(name = "notes")
    public String notes;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();

    public static ScriptVersion findByScriptAndNumber(Long scriptId, int number) {
        return find("scriptId = ?1 and versionNumber = ?2", scriptId, number).firstResult();
    }
}
