package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Singleton;

import com.fasterxml.jackson.annotation.JsonFormat;

import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.DeserializationConfig;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.KeyDeserializer;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.TypeDeserializer;
import tools.jackson.databind.module.SimpleDeserializers;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.module.SimpleSerializers;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.databind.type.CollectionLikeType;
import tools.jackson.databind.type.CollectionType;
import tools.jackson.databind.type.MapLikeType;
import tools.jackson.databind.type.MapType;

@Singleton
public class GeneratedSerializersRegister implements JsonMapperBuilderCustomizer {

    private static final SimpleModule mappingModule = new SimpleModule();
    private static final ExactSerializers serializers = new ExactSerializers();
    private static final PerCacheDeserializers deserializers = new PerCacheDeserializers();

    static {
        // Use a custom SimpleSerializers to use a json serializer only if it has been generated for that
        // exact class and not one of its sublclasses. This is already the default behaviour for deserializers.
        mappingModule.setSerializers(serializers);
        mappingModule.setDeserializers(deserializers);
    }

    @Override
    public void customize(JsonMapper.Builder builder) {
        builder.addModule(mappingModule);
    }

    public static void addSerializer(Class<? extends StdSerializer> serClass) {
        try {
            StdSerializer serializer = serClass.getConstructor().newInstance();
            serializers.addExactSerializer(serializer.handledType(), serializer);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static void addDeserializer(Class<? extends StdDeserializer> deserClass) {
        try {
            Constructor<? extends StdDeserializer> constructor = deserClass.getConstructor();
            StdDeserializer deserializer = constructor.newInstance();
            deserializers.addGeneratedDeserializer(deserializer.handledType(), deserializer, constructor);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Hands out a new instance of a generated deserializer every time Jackson asks for it, i.e. once for every
     * {@code DeserializerCache} (one per {@code ObjectMapper}) and type, like Jackson does with its own
     * {@code BeanDeserializer}s. A generated deserializer keeps in its fields the deserializers of the values it reads,
     * which it looks up in {@code ValueDeserializer#resolve} through the {@code ObjectMapper} that is going to use it,
     * so a single instance cannot be shared by mappers that may be configured differently.
     */
    public static class PerCacheDeserializers extends SimpleDeserializers {

        private final Map<Class<?>, Constructor<? extends StdDeserializer>> constructors = new HashMap<>();

        @SuppressWarnings("unchecked")
        void addGeneratedDeserializer(Class<?> type, StdDeserializer deserializer,
                Constructor<? extends StdDeserializer> constructor) {
            // the instance registered in the superclass is never used to deserialize, but it keeps working all the
            // methods of SimpleDeserializers, e.g. hasDeserializerFor, that are not overridden here
            addDeserializer((Class<Object>) type, deserializer);
            constructors.put(deserializer.getClass(), constructor);
        }

        private ValueDeserializer<?> newInstance(ValueDeserializer<?> deserializer) {
            if (deserializer == null) {
                return null;
            }
            Constructor<? extends StdDeserializer> constructor = constructors.get(deserializer.getClass());
            if (constructor == null) {
                return deserializer;
            }
            try {
                return constructor.newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public ValueDeserializer<?> findBeanDeserializer(JavaType type, DeserializationConfig config,
                BeanDescription.Supplier beanDescRef) {
            return newInstance(super.findBeanDeserializer(type, config, beanDescRef));
        }

        @Override
        public ValueDeserializer<?> findCollectionDeserializer(CollectionType type, DeserializationConfig config,
                BeanDescription.Supplier beanDescRef, TypeDeserializer elementTypeDeserializer,
                ValueDeserializer<?> elementDeserializer) {
            return newInstance(super.findCollectionDeserializer(type, config, beanDescRef, elementTypeDeserializer,
                    elementDeserializer));
        }

        @Override
        public ValueDeserializer<?> findCollectionLikeDeserializer(CollectionLikeType type, DeserializationConfig config,
                BeanDescription.Supplier beanDescRef, TypeDeserializer elementTypeDeserializer,
                ValueDeserializer<?> elementDeserializer) {
            return newInstance(super.findCollectionLikeDeserializer(type, config, beanDescRef, elementTypeDeserializer,
                    elementDeserializer));
        }

        @Override
        public ValueDeserializer<?> findMapDeserializer(MapType type, DeserializationConfig config,
                BeanDescription.Supplier beanDescRef, KeyDeserializer keyDeserializer,
                TypeDeserializer elementTypeDeserializer, ValueDeserializer<?> elementDeserializer) {
            return newInstance(super.findMapDeserializer(type, config, beanDescRef, keyDeserializer,
                    elementTypeDeserializer, elementDeserializer));
        }

        @Override
        public ValueDeserializer<?> findMapLikeDeserializer(MapLikeType type, DeserializationConfig config,
                BeanDescription.Supplier beanDescRef, KeyDeserializer keyDeserializer,
                TypeDeserializer elementTypeDeserializer, ValueDeserializer<?> elementDeserializer) {
            return newInstance(super.findMapLikeDeserializer(type, config, beanDescRef, keyDeserializer,
                    elementTypeDeserializer, elementDeserializer));
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
