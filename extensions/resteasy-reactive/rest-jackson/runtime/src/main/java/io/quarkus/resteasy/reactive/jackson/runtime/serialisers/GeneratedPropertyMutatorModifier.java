package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBuilder;
import com.fasterxml.jackson.databind.deser.BeanDeserializerModifier;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;

/**
 * Replaces the reflective {@link SettableBeanProperty}s of the beans that have a generated
 * {@link GeneratedPropertyAccessor} with {@link GeneratedSettableProperties}, and wraps the resulting
 * {@link BeanDeserializer} into a {@link GeneratedBeanDeserializer} so that field names are matched against
 * pre-encoded bytes in declaration order. Properties the generator did not cover keep Jackson's own implementation.
 */
public class GeneratedPropertyMutatorModifier extends BeanDeserializerModifier {

    private static final long serialVersionUID = 1L;

    private final Map<Class<?>, GeneratedPropertyAccessor> accessors;

    public GeneratedPropertyMutatorModifier(Map<Class<?>, GeneratedPropertyAccessor> accessors) {
        this.accessors = accessors;
    }

    @Override
    public BeanDeserializerBuilder updateBuilder(DeserializationConfig config, BeanDescription beanDesc,
            BeanDeserializerBuilder builder) {
        GeneratedPropertyAccessor accessor = accessors.get(beanDesc.getBeanClass());
        if (accessor == null) {
            return builder;
        }
        List<SettableBeanProperty> replacements = new ArrayList<>();
        for (Iterator<SettableBeanProperty> it = builder.getProperties(); it.hasNext();) {
            SettableBeanProperty property = it.next();
            AnnotatedMember member = property.getMember();
            if (member == null) {
                continue;
            }
            int index = accessor.indexOfSetter(member.getName());
            if (index < 0) {
                continue;
            }
            int kind = accessor.kindOf(index);
            if (!kindMatches(kind, property.getType().getRawClass())) {
                continue;
            }
            replacements.add(GeneratedSettableProperties.create(property, accessor, index, kind));
        }
        for (SettableBeanProperty replacement : replacements) {
            builder.addOrReplaceProperty(replacement, true);
        }
        return builder;
    }

    @Override
    public JsonDeserializer<?> modifyDeserializer(DeserializationConfig config, BeanDescription beanDesc,
            JsonDeserializer<?> deserializer) {
        if (!(deserializer instanceof BeanDeserializer beanDeserializer)
                || !accessors.containsKey(beanDesc.getBeanClass())) {
            return deserializer;
        }
        return new GeneratedBeanDeserializer(beanDeserializer);
    }

    private static boolean kindMatches(int kind, Class<?> rawType) {
        return switch (kind) {
            case GeneratedPropertyAccessor.KIND_STRING -> rawType == String.class;
            case GeneratedPropertyAccessor.KIND_INT -> rawType == int.class;
            case GeneratedPropertyAccessor.KIND_LONG -> rawType == long.class;
            case GeneratedPropertyAccessor.KIND_BOOLEAN -> rawType == boolean.class;
            case GeneratedPropertyAccessor.KIND_DOUBLE -> rawType == double.class;
            case GeneratedPropertyAccessor.KIND_OBJECT -> !rawType.isPrimitive();
            default -> false;
        };
    }
}
