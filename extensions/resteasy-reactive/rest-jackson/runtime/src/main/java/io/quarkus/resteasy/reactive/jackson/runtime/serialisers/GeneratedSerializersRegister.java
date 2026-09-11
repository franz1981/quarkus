package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.module.SimpleSerializers;
import tools.jackson.databind.ser.std.StdSerializer;

@Singleton
public class GeneratedSerializersRegister implements JsonMapperBuilderCustomizer {

    private static final List<Class<? extends StdSerializer>> serializerClasses = new CopyOnWriteArrayList<>();
    private static final List<Class<? extends StdDeserializer>> deserializerClasses = new CopyOnWriteArrayList<>();

    @Override
    public void customize(JsonMapper.Builder builder) {
        // Every mapper gets its own instances: a generated serializer resolves the serializers of its nested bean
        // types once per mapper, in ValueSerializer#resolve, so instances cannot be shared between mappers.
        SimpleModule mappingModule = new SimpleModule();
        // Use a custom SimpleSerializers to use a json serializer only if it has been generated for that
        // exact class and not one of its sublclasses. This is already the default behaviour for deserializers.
        ExactSerializers serializers = new ExactSerializers();
        for (Class<? extends StdSerializer> serClass : serializerClasses) {
            StdSerializer serializer = newInstance(serClass);
            serializers.addExactSerializer(serializer.handledType(), serializer);
        }
        mappingModule.setSerializers(serializers);
        for (Class<? extends StdDeserializer> deserClass : deserializerClasses) {
            StdDeserializer deserializer = newInstance(deserClass);
            mappingModule.addDeserializer(deserializer.handledType(), deserializer);
        }
        builder.addModule(mappingModule);
    }

    public static void addSerializer(Class<? extends StdSerializer> serClass) {
        serializerClasses.add(serClass);
    }

    public static void addDeserializer(Class<? extends StdDeserializer> deserClass) {
        deserializerClasses.add(deserClass);
    }

    private static <T> T newInstance(Class<T> clazz) {
        try {
            return clazz.getConstructor().newInstance();
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static class ExactSerializers extends SimpleSerializers {

        private final Map<Class<?>, ValueSerializer<?>> exactSerializers = new HashMap<>();

        public <T> void addExactSerializer(Class<? extends T> type, ValueSerializer<T> ser) {
            exactSerializers.put(type, ser);
        }

        @Override
        public ValueSerializer<?> findSerializer(SerializationConfig config,
                JavaType type, BeanDescription.Supplier beanDescRef, JsonFormat.Value formatOverrides) {
            ValueSerializer<?> exactSerializer = exactSerializers.get(type.getRawClass());
            return exactSerializer != null ? exactSerializer : super.findSerializer(config, type, beanDescRef, formatOverrides);
        }
    }
}
