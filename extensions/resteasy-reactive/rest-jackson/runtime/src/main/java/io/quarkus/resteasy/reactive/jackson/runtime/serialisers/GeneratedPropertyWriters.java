package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.PropertySerializerMap;
import com.fasterxml.jackson.databind.ser.std.BooleanSerializer;
import com.fasterxml.jackson.databind.ser.std.NumberSerializers;
import com.fasterxml.jackson.databind.ser.std.StringSerializer;

/**
 * {@link BeanPropertyWriter}s that read the property through a {@link GeneratedPropertyAccessor} instead of
 * reflection and write scalars straight to the generator when the property uses Jackson's default serializer, no
 * inclusion filtering and no type serializer; every other case follows {@code BeanPropertyWriter#serializeAsField}
 * exactly, with the value coming from the accessor.
 */
public final class GeneratedPropertyWriters {

    private GeneratedPropertyWriters() {
    }

    public static BeanPropertyWriter create(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index,
            int kind) {
        return switch (kind) {
            case GeneratedPropertyAccessor.KIND_STRING -> new StringWriter(base, accessor, index);
            case GeneratedPropertyAccessor.KIND_INT -> new IntWriter(base, accessor, index);
            case GeneratedPropertyAccessor.KIND_LONG -> new LongWriter(base, accessor, index);
            case GeneratedPropertyAccessor.KIND_BOOLEAN -> new BooleanWriter(base, accessor, index);
            case GeneratedPropertyAccessor.KIND_DOUBLE -> new DoubleWriter(base, accessor, index);
            case GeneratedPropertyAccessor.KIND_OBJECT -> new ObjectWriter(base, accessor, index);
            default -> base;
        };
    }

    abstract static class GeneratedPropertyWriter extends BeanPropertyWriter {
        protected final GeneratedPropertyAccessor accessor;
        protected final int index;

        GeneratedPropertyWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base);
            this.accessor = accessor;
            this.index = index;
        }

        /** {@code BeanPropertyWriter#serializeAsField} from the point where the value has been read. */
        protected final void writeValue(Object bean, Object value, JsonGenerator gen, SerializerProvider prov)
                throws Exception {
            if (value == null) {
                if ((_suppressableValue != null) && prov.includeFilterSuppressNulls(_suppressableValue)) {
                    return;
                }
                if (_nullSerializer != null) {
                    gen.writeFieldName(_name);
                    _nullSerializer.serialize(null, gen, prov);
                }
                return;
            }
            JsonSerializer<Object> ser = _serializer;
            if (ser == null) {
                Class<?> cls = value.getClass();
                PropertySerializerMap m = _dynamicSerializers;
                ser = m.serializerFor(cls);
                if (ser == null) {
                    ser = _findAndAddDynamic(m, cls, prov);
                }
            }
            if (_suppressableValue != null) {
                if (MARKER_FOR_EMPTY == _suppressableValue) {
                    if (ser.isEmpty(prov, value)) {
                        return;
                    }
                } else if (_suppressableValue.equals(value)) {
                    return;
                }
            }
            if (value == bean) {
                if (_handleSelfReference(bean, gen, prov, ser)) {
                    return;
                }
            }
            gen.writeFieldName(_name);
            if (_typeSerializer == null) {
                ser.serialize(value, gen, prov);
            } else {
                ser.serializeWithType(value, gen, prov, _typeSerializer);
            }
        }

        protected final boolean plain(Class<?> defaultSerializer) {
            return _suppressableValue == null && _typeSerializer == null
                    && (_serializer == null || _serializer.getClass() == defaultSerializer);
        }
    }

    static final class StringWriter extends GeneratedPropertyWriter {
        StringWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base, accessor, index);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            String value = accessor.stringGetter(bean, index);
            if (value == null || !plain(StringSerializer.class)) {
                writeValue(bean, value, gen, prov);
                return;
            }
            gen.writeFieldName(_name);
            gen.writeString(value);
        }
    }

    static final class IntWriter extends GeneratedPropertyWriter {
        IntWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base, accessor, index);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            int value = accessor.intGetter(bean, index);
            if (!plain(NumberSerializers.IntegerSerializer.class)) {
                writeValue(bean, value, gen, prov);
                return;
            }
            gen.writeFieldName(_name);
            gen.writeNumber(value);
        }
    }

    static final class LongWriter extends GeneratedPropertyWriter {
        LongWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base, accessor, index);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            long value = accessor.longGetter(bean, index);
            if (!plain(NumberSerializers.LongSerializer.class)) {
                writeValue(bean, value, gen, prov);
                return;
            }
            gen.writeFieldName(_name);
            gen.writeNumber(value);
        }
    }

    static final class BooleanWriter extends GeneratedPropertyWriter {
        BooleanWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base, accessor, index);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            boolean value = accessor.booleanGetter(bean, index);
            if (!plain(BooleanSerializer.class)) {
                writeValue(bean, value, gen, prov);
                return;
            }
            gen.writeFieldName(_name);
            gen.writeBoolean(value);
        }
    }

    static final class DoubleWriter extends GeneratedPropertyWriter {
        DoubleWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base, accessor, index);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            double value = accessor.doubleGetter(bean, index);
            if (!plain(NumberSerializers.DoubleSerializer.class)) {
                writeValue(bean, value, gen, prov);
                return;
            }
            gen.writeFieldName(_name);
            gen.writeNumber(value);
        }
    }

    static final class ObjectWriter extends GeneratedPropertyWriter {
        ObjectWriter(BeanPropertyWriter base, GeneratedPropertyAccessor accessor, int index) {
            super(base, accessor, index);
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            writeValue(bean, accessor.objectGetter(bean, index), gen, prov);
        }
    }
}
