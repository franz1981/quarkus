package io.quarkus.resteasy.reactive.jackson.runtime.serialisers;

/**
 * Base class of the build-time generated property accessors: one subclass per serialized bean, with one
 * {@code switch(index)} per value kind that invokes the bean's getters directly. The generated serializer support does
 * not replace Jackson's {@code BeanSerializer}; it only replaces the reflective property access and the per-value
 * serializer lookups of its {@code BeanPropertyWriter}s (see {@link GeneratedPropertyWriters}), so every property write
 * stays a small, separately compiled method.
 */
public abstract class GeneratedPropertyAccessor {

    public static final int KIND_STRING = 1;
    public static final int KIND_INT = 2;
    public static final int KIND_LONG = 3;
    public static final int KIND_BOOLEAN = 4;
    public static final int KIND_DOUBLE = 5;
    public static final int KIND_OBJECT = 6;

    private final Class<?> beanClass;

    protected GeneratedPropertyAccessor(Class<?> beanClass) {
        this.beanClass = beanClass;
    }

    public final Class<?> beanClass() {
        return beanClass;
    }

    /**
     * @return the index of the property read through the given Java member (getter method or public field name), or -1
     */
    public abstract int indexOf(String memberName);

    /**
     * @return one of the {@code KIND_*} constants for the property at the given index
     */
    public abstract int kindOf(int index);

    public String stringGetter(Object bean, int index) {
        throw unsupported(index);
    }

    public int intGetter(Object bean, int index) {
        throw unsupported(index);
    }

    public long longGetter(Object bean, int index) {
        throw unsupported(index);
    }

    public boolean booleanGetter(Object bean, int index) {
        throw unsupported(index);
    }

    public double doubleGetter(Object bean, int index) {
        throw unsupported(index);
    }

    public Object objectGetter(Object bean, int index) {
        throw unsupported(index);
    }

    protected final IllegalStateException unsupported(int index) {
        return new IllegalStateException("No generated accessor for property " + index + " of " + beanClass.getName());
    }
}
