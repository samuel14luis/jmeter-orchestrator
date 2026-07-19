package com.performance.orchestrator.domain;

import java.time.Instant;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Traza de auditoria (seccion 10 del plan): quien hizo que y cuando. */
@Entity
@Table(name = "audit_event")
public class AuditEvent extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "at", nullable = false)
    public Instant at = Instant.now();

    @Column(name = "actor")
    public String actor;

    @Column(name = "action", nullable = false, length = 60)
    public String action;

    @Column(name = "entity_type", nullable = false, length = 60)
    public String entityType;

    @Column(name = "entity_id")
    public Long entityId;

    @Column(name = "detail", columnDefinition = "NVARCHAR(MAX)")
    public String detail;

    public static void record(String actor, String action, String entityType, Long entityId, String detail) {
        AuditEvent e = new AuditEvent();
        e.actor = actor;
        e.action = action;
        e.entityType = entityType;
        e.entityId = entityId;
        e.detail = detail;
        e.persist();
    }
}
