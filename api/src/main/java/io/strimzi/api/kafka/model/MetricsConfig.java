/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.strimzi.crdgenerator.annotations.Description;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * Describes the metrics configuration
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(name = JmxPrometheusExporterMetrics.TYPE_JMX_EXPORTER, value = JmxPrometheusExporterMetrics.class),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode
public abstract class MetricsConfig implements UnknownPropertyPreserving, Serializable {

    private static final long serialVersionUID = 1L;

    private Map<String, Object> additionalProperties = new HashMap<>(0);

    @Description("Metrics type. Only 'jmxPrometheusExporter' supported currently.")
    public abstract String getType();

    @Override
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @Override
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}

