package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import java.io.IOException;
import java.lang.annotation.Annotation;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.PropertyName;
import com.fasterxml.jackson.databind.deser.NullValueProvider;
import com.fasterxml.jackson.databind.deser.SettableBeanProperty;
import com.fasterxml.jackson.databind.deser.impl.NullsConstantProvider;
import com.fasterxml.jackson.databind.deser.std.NumberDeserializers;
import com.fasterxml.jackson.databind.deser.std.StringDeserializer;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;

/**
 * {@link SettableBeanProperty} implementations that write the value through a {@link GeneratedPropertyAccessor}
 * instead of reflection. A scalar is read straight from the parser only when Jackson picked its own default
 * deserializer for the property, there is no polymorphic type deserializer and no view; every other case,
 * nulls included, follows {@code MethodProperty#deserializeAndSet} and only the write goes through the
 * accessor.
 */
public final class GeneratedSettableProperties {

    private GeneratedSettableProperties() {
    }

    public static SettableBeanProperty create(SettableBeanProperty delegate, GeneratedPropertyAccessor accessor,
            int index, int kind) {
        return switch (kind) {
            case GeneratedPropertyAccessor.KIND_STRING -> new StringProperty(delegate, accessor, index);
            case GeneratedPropertyAccessor.KIND_INT -> new IntProperty(delegate, accessor, index);
            case GeneratedPropertyAccessor.KIND_LONG -> new LongProperty(delegate, accessor, index);
            case GeneratedPropertyAccessor.KIND_BOOLEAN -> new BooleanProperty(delegate, accessor, index);
            case GeneratedPropertyAccessor.KIND_DOUBLE -> new DoubleProperty(delegate, accessor, index);
            case GeneratedPropertyAccessor.KIND_OBJECT -> new ObjectProperty(delegate, accessor, index);
            default -> delegate;
        };
    }

    abstract static class GeneratedSettableProperty extends SettableBeanProperty {

        private static final long serialVersionUID = 1L;

        protected final SettableBeanProperty delegate;
        protected final GeneratedPropertyAccessor accessor;
        protected final int index;

        /** {@code MethodProperty#_skipNulls}: {@code @JsonSetter(nulls = Nulls.SKIP)} leaves the property untouched */
        private final boolean skipNulls;
        private final boolean fastPath;

        GeneratedSettableProperty(SettableBeanProperty delegate, GeneratedPropertyAccessor accessor, int index,
                Class<?> defaultValueDeserializer) {
            super(delegate);
            this.delegate = delegate;
            this.accessor = accessor;
            this.index = index;
            this.skipNulls = NullsConstantProvider.isSkipper(_nullProvider);
            this.fastPath = defaultValueDeserializer != null && _valueTypeDeserializer == null && _viewMatcher == null
                    && _valueDeserializer != null && _valueDeserializer.getClass() == defaultValueDeserializer;
        }

        /**
         * Whether the scalar may be read straight from the parser: Jackson picked its own default deserializer for
         * this property, so reading the value here and letting it deserialize produce the same result. Nulls are not
         * covered by this and always go through {@link #deserializeAndSet}.
         */
        protected final boolean canReadDirectly() {
            return fastPath;
        }

        protected abstract GeneratedSettableProperty withDelegate(SettableBeanProperty newDelegate);

        @Override
        public SettableBeanProperty withValueDeserializer(JsonDeserializer<?> deser) {
            return withDelegate(delegate.withValueDeserializer(deser));
        }

        @Override
        public SettableBeanProperty withName(PropertyName newName) {
            return withDelegate(delegate.withName(newName));
        }

        @Override
        public SettableBeanProperty withNullProvider(NullValueProvider nva) {
            return withDelegate(delegate.withNullProvider(nva));
        }

        @Override
        public AnnotatedMember getMember() {
            return delegate.getMember();
        }

        @Override
        public <A extends Annotation> A getAnnotation(Class<A> acls) {
            return delegate.getAnnotation(acls);
        }

        /** {@code MethodProperty#deserializeAndSet}, with the value written through the accessor. */
        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object bean) throws IOException {
            Object value;
            if (p.hasToken(JsonToken.VALUE_NULL)) {
                if (skipNulls) {
                    return;
                }
                value = _nullProvider.getNullValue(ctxt);
            } else if (_valueTypeDeserializer == null) {
                value = _valueDeserializer.deserialize(p, ctxt);
                if (value == null) {
                    if (skipNulls) {
                        return;
                    }
                    value = _nullProvider.getNullValue(ctxt);
                }
            } else {
                value = _valueDeserializer.deserializeWithType(p, ctxt, _valueTypeDeserializer);
            }
            set(bean, value);
        }

        @Override
        public Object deserializeSetAndReturn(JsonParser p, DeserializationContext ctxt, Object bean)
                throws IOException {
            deserializeAndSet(p, ctxt, bean);
            return bean;
        }

        @Override
        public Object setAndReturn(Object instance, Object value) throws IOException {
            set(instance, value);
            return instance;
        }

    }

    static final class StringProperty extends GeneratedSettableProperty {
        private static final long serialVersionUID = 1L;

        StringProperty(SettableBeanProperty d, GeneratedPropertyAccessor a, int i) {
            super(d, a, i, StringDeserializer.class);
        }

        @Override
        protected GeneratedSettableProperty withDelegate(SettableBeanProperty d) {
            return new StringProperty(d, accessor, index);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object bean) throws IOException {
            if (canReadDirectly() && (p.hasToken(JsonToken.VALUE_STRING))) {
                accessor.stringSetter(bean, index, p.getText());
                return;
            }
            super.deserializeAndSet(p, ctxt, bean);
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            accessor.stringSetter(instance, index, (String) value);
        }
    }

    static final class IntProperty extends GeneratedSettableProperty {
        private static final long serialVersionUID = 1L;

        IntProperty(SettableBeanProperty d, GeneratedPropertyAccessor a, int i) {
            super(d, a, i, NumberDeserializers.IntegerDeserializer.class);
        }

        @Override
        protected GeneratedSettableProperty withDelegate(SettableBeanProperty d) {
            return new IntProperty(d, accessor, index);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object bean) throws IOException {
            if (canReadDirectly() && (p.hasToken(JsonToken.VALUE_NUMBER_INT))) {
                accessor.intSetter(bean, index, p.getIntValue());
                return;
            }
            super.deserializeAndSet(p, ctxt, bean);
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            accessor.intSetter(instance, index, value == null ? 0 : ((Number) value).intValue());
        }
    }

    static final class LongProperty extends GeneratedSettableProperty {
        private static final long serialVersionUID = 1L;

        LongProperty(SettableBeanProperty d, GeneratedPropertyAccessor a, int i) {
            super(d, a, i, NumberDeserializers.LongDeserializer.class);
        }

        @Override
        protected GeneratedSettableProperty withDelegate(SettableBeanProperty d) {
            return new LongProperty(d, accessor, index);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object bean) throws IOException {
            if (canReadDirectly() && (p.hasToken(JsonToken.VALUE_NUMBER_INT))) {
                accessor.longSetter(bean, index, p.getLongValue());
                return;
            }
            super.deserializeAndSet(p, ctxt, bean);
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            accessor.longSetter(instance, index, value == null ? 0L : ((Number) value).longValue());
        }
    }

    static final class BooleanProperty extends GeneratedSettableProperty {
        private static final long serialVersionUID = 1L;

        BooleanProperty(SettableBeanProperty d, GeneratedPropertyAccessor a, int i) {
            super(d, a, i, NumberDeserializers.BooleanDeserializer.class);
        }

        @Override
        protected GeneratedSettableProperty withDelegate(SettableBeanProperty d) {
            return new BooleanProperty(d, accessor, index);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object bean) throws IOException {
            JsonToken t = p.currentToken();
            if (canReadDirectly() && (t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE)) {
                accessor.booleanSetter(bean, index, t == JsonToken.VALUE_TRUE);
                return;
            }
            super.deserializeAndSet(p, ctxt, bean);
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            accessor.booleanSetter(instance, index, value != null && (Boolean) value);
        }
    }

    static final class DoubleProperty extends GeneratedSettableProperty {
        private static final long serialVersionUID = 1L;

        DoubleProperty(SettableBeanProperty d, GeneratedPropertyAccessor a, int i) {
            super(d, a, i, NumberDeserializers.DoubleDeserializer.class);
        }

        @Override
        protected GeneratedSettableProperty withDelegate(SettableBeanProperty d) {
            return new DoubleProperty(d, accessor, index);
        }

        @Override
        public void deserializeAndSet(JsonParser p, DeserializationContext ctxt, Object bean) throws IOException {
            if (canReadDirectly() && (p.hasToken(JsonToken.VALUE_NUMBER_FLOAT) || p.hasToken(JsonToken.VALUE_NUMBER_INT))) {
                accessor.doubleSetter(bean, index, p.getDoubleValue());
                return;
            }
            super.deserializeAndSet(p, ctxt, bean);
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            accessor.doubleSetter(instance, index, value == null ? 0d : ((Number) value).doubleValue());
        }
    }

    static final class ObjectProperty extends GeneratedSettableProperty {
        private static final long serialVersionUID = 1L;

        ObjectProperty(SettableBeanProperty d, GeneratedPropertyAccessor a, int i) {
            super(d, a, i, null);
        }

        @Override
        protected GeneratedSettableProperty withDelegate(SettableBeanProperty d) {
            return new ObjectProperty(d, accessor, index);
        }

        @Override
        public void set(Object instance, Object value) throws IOException {
            accessor.objectSetter(instance, index, value);
        }
    }
}
