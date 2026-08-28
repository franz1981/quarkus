package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializer;
import com.fasterxml.jackson.databind.deser.BeanDeserializerBase;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.impl.BeanPropertyMap;
import com.fasterxml.jackson.databind.deser.impl.ObjectIdReader;
import com.fasterxml.jackson.databind.util.NameTransformer;

/**
 * {@link BeanDeserializer} that first tries to match the incoming field names, in declaration order, against the
 * pre-encoded property names: {@code JsonParser#nextFieldName(SerializableString)} compares the raw bytes in the parser
 * buffer, so the common case (a document written with the same property order, e.g. by the matching serializer) never
 * materialises the field name as a {@code String}. Anything else - a different order, an unknown field, a shape this
 * deserializer does not handle - falls back to the standard implementation.
 */
public class GeneratedBeanDeserializer extends BeanDeserializer {

    private static final long serialVersionUID = 1L;

    /**
     * The properties are only usable once {@link #resolve(DeserializationContext)} has assigned their value
     * deserializers, and resolution replaces the property instances, so the ordered view is built on first use
     * rather than at construction time.
     */
    private transient volatile OrderedProperties ordered;

    private transient NameTransformer currentlyTransforming;

    public GeneratedBeanDeserializer(BeanDeserializerBase src) {
        super(src);
    }

    protected GeneratedBeanDeserializer(GeneratedBeanDeserializer src, boolean ignoreAllUnknown) {
        super(src, ignoreAllUnknown);
    }

    protected GeneratedBeanDeserializer(GeneratedBeanDeserializer src, NameTransformer unwrapper) {
        super(src, unwrapper);
    }

    protected GeneratedBeanDeserializer(GeneratedBeanDeserializer src, ObjectIdReader oir) {
        super(src, oir);
    }

    protected GeneratedBeanDeserializer(GeneratedBeanDeserializer src, Set<String> ignorableProps,
            Set<String> includableProps) {
        super(src, ignorableProps, includableProps);
    }

    protected GeneratedBeanDeserializer(GeneratedBeanDeserializer src, BeanPropertyMap props) {
        super(src, props);
    }

    @Override
    public BeanDeserializer withIgnoreAllUnknown(boolean ignoreUnknown) {
        return new GeneratedBeanDeserializer(this, ignoreUnknown);
    }

    @Override
    public BeanDeserializer withObjectIdReader(ObjectIdReader oir) {
        return new GeneratedBeanDeserializer(this, oir);
    }

    @Override
    public BeanDeserializer withByNameInclusion(Set<String> ignorableProps, Set<String> includableProps) {
        return new GeneratedBeanDeserializer(this, ignorableProps, includableProps);
    }

    @Override
    public BeanDeserializer withBeanProperties(BeanPropertyMap props) {
        return new GeneratedBeanDeserializer(this, props);
    }

    /**
     * {@link BeanDeserializer#unwrappingDeserializer(NameTransformer)} returns {@code this} for any subclass, which
     * would silently drop {@code @JsonUnwrapped} handling, so the transformed copy is created here instead. The
     * copy has {@code _vanillaProcessing} cleared, so it never takes the ordered path.
     */
    @Override
    public JsonDeserializer<Object> unwrappingDeserializer(NameTransformer transformer) {
        if (currentlyTransforming == transformer) {
            // a self-referential @JsonUnwrapped, see databind#383
            return this;
        }
        currentlyTransforming = transformer;
        try {
            return new GeneratedBeanDeserializer(this, transformer);
        } finally {
            currentlyTransforming = null;
        }
    }

    @Override
    public Object deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        if (!_vanillaProcessing || _objectIdReader != null || _nonStandardCreation || _needViewProcesing
                || !p.isExpectedStartObjectToken()) {
            return super.deserialize(p, ctxt);
        }
        OrderedProperties properties = ordered();
        if (properties.names.length == 0) {
            return super.deserialize(p, ctxt);
        }
        final Object bean = _valueInstantiator.createUsingDefault(ctxt);
        for (int i = 0, len = properties.names.length; i < len; i++) {
            if (!p.nextFieldName(properties.names[i])) {
                if (p.currentToken() == JsonToken.END_OBJECT) {
                    return bean;
                }
                // the document does not follow the declaration order: finish with the standard implementation
                return super.deserialize(p, ctxt, bean);
            }
            if (i == 0) {
                // as vanillaDeserialize does, assign only once the object is known to have a property (databind#4184)
                p.assignCurrentValue(bean);
            }
            p.nextToken();
            SettableBeanProperty property = properties.properties[i];
            try {
                property.deserializeAndSet(p, ctxt, bean);
            } catch (Exception e) {
                wrapAndThrow(e, bean, property.getName(), ctxt);
            }
        }
        if (p.nextToken() != JsonToken.END_OBJECT) {
            return super.deserialize(p, ctxt, bean);
        }
        return bean;
    }

    private OrderedProperties ordered() {
        OrderedProperties current = ordered;
        if (current == null) {
            List<SerializedString> names = new ArrayList<>();
            List<SettableBeanProperty> properties = new ArrayList<>();
            for (SettableBeanProperty property : _beanProperties.getPropertiesInInsertionOrder()) {
                if (property == null) {
                    // removed properties leave holes in the insertion order
                    continue;
                }
                names.add(new SerializedString(property.getName()));
                properties.add(property);
            }
            current = new OrderedProperties(names.toArray(new SerializedString[0]),
                    properties.toArray(new SettableBeanProperty[0]));
            ordered = current;
        }
        return current;
    }

    private record OrderedProperties(SerializedString[] names, SettableBeanProperty[] properties) {
    }
}
