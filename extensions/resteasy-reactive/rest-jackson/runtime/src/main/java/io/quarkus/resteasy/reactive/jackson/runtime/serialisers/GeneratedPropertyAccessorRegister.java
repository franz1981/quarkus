package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Singleton;

import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * Installs the build-time generated {@link GeneratedPropertyAccessor} into every {@code ObjectMapper}. It is a
 * the recorder fills it in at static init and clears it on shutdown, so a generated accessor never outlives the
 * application it was generated for - which matters in dev mode, where this class is loaded by the base runtime
 * classloader and survives a live reload, while the accessor and the beans do not.
 * <p>
 * Jackson keeps
 * building and driving its own bean serializers and deserializers, while {@link GeneratedPropertyWriterModifier} and
 * {@link GeneratedPropertyMutatorModifier} replace their reflective property access with calls into the accessor.
 */
@Singleton
public class GeneratedPropertyAccessorRegister implements JsonMapperBuilderCustomizer {

    private static volatile List<Class<?>> beanClasses = List.of();
    private static volatile List<Class<? extends GeneratedPropertyAccessor>> accessorClasses = List.of();

    @Override
    public void customize(JsonMapper.Builder builder) {
        List<Class<? extends GeneratedPropertyAccessor>> generated = accessorClasses;
        List<Class<?>> beans = beanClasses;
        if (generated.isEmpty() || beans.isEmpty()) {
            return;
        }
        // one generated class per bean, paired positionally
        Map<Class<?>, GeneratedPropertyAccessor> accessors = new HashMap<>();
        for (int i = 0; i < beans.size(); i++) {
            Class<?> beanClass = beans.get(i);
            accessors.put(beanClass, newInstance(generated.get(i), beanClass));
        }
        SimpleModule module = new SimpleModule();
        module.setSerializerModifier(new GeneratedPropertyWriterModifier(accessors));
        module.setDeserializerModifier(new GeneratedPropertyMutatorModifier(accessors));
        builder.addModule(module);
    }

    public static void setPropertyAccessors(List<Class<? extends GeneratedPropertyAccessor>> generated,
            List<Class<?>> generatedBeanClasses) {
        // the pairing is positional, so both lists are replaced rather than grown
        beanClasses = List.copyOf(generatedBeanClasses);
        accessorClasses = List.copyOf(generated);
    }

    /**
     * Dev mode loads this class from the base runtime classloader, so it outlives the application whose accessor and
     * beans it points at; the recorder clears it on shutdown, as it does for its other static state.
     */
    public static void clear() {
        accessorClasses = List.of();
        beanClasses = List.of();
    }

    private static GeneratedPropertyAccessor newInstance(Class<? extends GeneratedPropertyAccessor> clazz,
            Class<?> beanClass) {
        try {
            return clazz.getConstructor(Class.class).newInstance(beanClass);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException
                | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
