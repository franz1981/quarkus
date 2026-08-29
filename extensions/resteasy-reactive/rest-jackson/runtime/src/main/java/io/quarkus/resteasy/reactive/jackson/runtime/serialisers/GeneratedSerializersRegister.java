package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.module.SimpleSerializers;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import io.quarkus.jackson.ObjectMapperCustomizer;

@Singleton
public class GeneratedSerializersRegister implements ObjectMapperCustomizer {

    private static final List<Class<? extends StdSerializer>> serializerClasses = new CopyOnWriteArrayList<>();
    private static final List<Class<? extends StdDeserializer>> deserializerClasses = new CopyOnWriteArrayList<>();
    private static final List<Class<? extends GeneratedPropertyAccessor>> accessorClasses = new CopyOnWriteArrayList<>();

    @Override
    public void customize(ObjectMapper objectMapper) {
        // Every ObjectMapper gets its own instances: the generated (de)serializers resolve the codecs of their nested
        // types once per mapper (ResolvableSerializer / ResolvableDeserializer), so instances cannot be shared.
        SimpleModule mappingModule = new SimpleModule();
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
        if (!accessorClasses.isEmpty()) {
            Map<Class<?>, GeneratedPropertyAccessor> accessors = new HashMap<>();
            for (Class<? extends GeneratedPropertyAccessor> accessorClass : accessorClasses) {
                GeneratedPropertyAccessor accessor = newInstance(accessorClass);
                accessors.put(accessor.beanClass(), accessor);
            }
            mappingModule.setSerializerModifier(new GeneratedPropertyWriterModifier(accessors));
        }
        objectMapper.registerModule(mappingModule);
    }

    public static void addPropertyAccessor(Class<? extends GeneratedPropertyAccessor> accessorClass) {
        accessorClasses.add(accessorClass);
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

        private final Map<Class<?>, JsonSerializer<?>> exactSerializers = new HashMap<>();

        public <T> void addExactSerializer(Class<? extends T> type, JsonSerializer<T> ser) {
            exactSerializers.put(type, ser);
        }

        @Override
        public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type, BeanDescription beanDesc) {
            JsonSerializer<?> exactSerializer = exactSerializers.get(type.getRawClass());
            return exactSerializer != null ? exactSerializer : super.findSerializer(config, type, beanDesc);
        }
    }
}
