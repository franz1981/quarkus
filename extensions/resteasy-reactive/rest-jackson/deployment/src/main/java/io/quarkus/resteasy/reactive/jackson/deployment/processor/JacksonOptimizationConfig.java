package io.quarkus.resteasy.reactive.jackson.deployment.processor;

import java.util.function.BooleanSupplier;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/**
 * Jackson optimization configuration.
 */
@ConfigMapping(prefix = "quarkus.rest.jackson.optimization")
@ConfigRoot(phase = ConfigPhase.BUILD_TIME)
public interface JacksonOptimizationConfig {

    /**
     * Enable build time generation of a reflection-free property accessor, used by Jackson to read and write
     * the properties of the classes serialized and deserialized by REST endpoints.
     */
    @WithDefault("true")
    boolean enableReflectionFreeSerializers();

    class IsReflectionFreeSerializersEnabled implements BooleanSupplier {
        JacksonOptimizationConfig config;

        public boolean getAsBoolean() {
            return config.enableReflectionFreeSerializers();
        }
    }
}
