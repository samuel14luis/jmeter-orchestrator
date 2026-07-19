package com.performance.orchestrator.domain;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "preset")
public class Preset extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "name", nullable = false)
    public String name;

    @Column(name = "script_id", nullable = false)
    public Long scriptId;

    @Enumerated(EnumType.STRING)
    @Column(name = "test_type", nullable = false, length = 20)
    public TestType testType;

    @Convert(converter = JsonMapConverter.class)
    @Column(name = "parameters_json", nullable = false, columnDefinition = "NVARCHAR(MAX)")
    public Map<String, Object> parameters = new LinkedHashMap<>();

    @Column(name = "target_env")
    public String targetEnv;

    @Column(name = "created_by")
    public String createdBy;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt = Instant.now();
}
