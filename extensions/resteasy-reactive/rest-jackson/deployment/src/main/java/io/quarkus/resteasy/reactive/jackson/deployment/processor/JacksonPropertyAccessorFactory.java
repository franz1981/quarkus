package io.quarkus.resteasy.reactive.jackson.deployment.processor;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import com.fasterxml.jackson.databind.PropertyNamingStrategy;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.gizmo.Switch;
import io.quarkus.resteasy.reactive.jackson.runtime.serialisers.GeneratedPropertyAccessor;

/**
 * Generates a {@link GeneratedPropertyAccessor} per serialized bean, for instance
 *
 * <pre>{@code
 * public class Person$quarkusjacksonaccessor extends GeneratedPropertyAccessor {
 *     public int indexOf(String member) { switch (member) { case "getFirstName": return 0; case "getAge": return 1; ... default: return -1; } }
 *     public int kindOf(int index) { ... }
 *     public String stringGetter(Object bean, int index) { if (index == 0) return ((Person) bean).getFirstName(); ...; return super.stringGetter(bean, index); }
 *     public int intGetter(Object bean, int index) { if (index == 1) return ((Person) bean).getAge(); return super.intGetter(bean, index); }
 *     public Object objectGetter(Object bean, int index) { ... }
 * }
 * }</pre>
 *
 * Jackson's own {@code BeanSerializer} keeps driving the serialization; at runtime
 * {@code GeneratedPropertyWriterModifier} replaces its {@code BeanPropertyWriter}s with writers that read the values
 * through this accessor and write scalars directly, so each property write remains a small separately compiled method.
 */
public class JacksonPropertyAccessorFactory extends JacksonSerializerFactory {

    private static final String CLASS_NAME_SUFFIX = "$quarkusjacksonaccessor";
    private static final String[] NO_INTERFACES = new String[0];

    public JacksonPropertyAccessorFactory(BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer,
            IndexView jandexIndex) {
        super(generatedClassBuildItemBuildProducer, jandexIndex);
    }

    @Override
    protected String getSuperClassName() {
        return GeneratedPropertyAccessor.class.getName();
    }

    @Override
    protected String getClassSuffix() {
        return CLASS_NAME_SUFFIX;
    }

    @Override
    protected String[] getInterfacesNames(ClassInfo classInfo) {
        return NO_INTERFACES;
    }

    private record Property(FieldSpecs fieldSpecs, String memberName, int kind) {
    }

    @Override
    protected boolean createSerializationMethod(ClassInfo classInfo, ClassCreator classCreator, String beanClassName) {
        PropertyNamingStrategy namingStrategy = getNamingStrategy(classInfo);
        List<Property> properties = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FieldSpecs fieldSpecs : collectAllFieldSpecs(classInfo, namingStrategy)) {
            if (fieldSpecs.isIgnoredField() || fieldSpecs.isBackReference() || isFieldTypeIgnored(fieldSpecs)) {
                continue;
            }
            String memberName;
            if (fieldSpecs.methodInfo != null) {
                memberName = fieldSpecs.methodInfo.name();
            } else if (fieldSpecs.fieldInfo != null && fieldSpecs.isPublicField()) {
                memberName = fieldSpecs.fieldInfo.name();
            } else {
                continue;
            }
            int kind = kindOf(fieldSpecs.fieldType);
            if (kind < 0 || !seen.add(memberName)) {
                continue;
            }
            if (kind == GeneratedPropertyAccessor.KIND_OBJECT) {
                registerTypeToBeGenerated(fieldSpecs.fieldType, fieldSpecs.fieldType.name().toString());
            }
            properties.add(new Property(fieldSpecs, memberName, kind));
        }
        if (properties.isEmpty()) {
            return false;
        }

        createIndexOf(classCreator, properties);
        createKindOf(classCreator, properties);
        createGetter(classCreator, beanClassName, "stringGetter", String.class, GeneratedPropertyAccessor.KIND_STRING,
                properties);
        createGetter(classCreator, beanClassName, "intGetter", int.class, GeneratedPropertyAccessor.KIND_INT, properties);
        createGetter(classCreator, beanClassName, "longGetter", long.class, GeneratedPropertyAccessor.KIND_LONG,
                properties);
        createGetter(classCreator, beanClassName, "booleanGetter", boolean.class, GeneratedPropertyAccessor.KIND_BOOLEAN,
                properties);
        createGetter(classCreator, beanClassName, "doubleGetter", double.class, GeneratedPropertyAccessor.KIND_DOUBLE,
                properties);
        createGetter(classCreator, beanClassName, "objectGetter", Object.class, GeneratedPropertyAccessor.KIND_OBJECT,
                properties);
        return true;
    }

    private static int kindOf(Type type) {
        if (type.kind() == Type.Kind.PRIMITIVE) {
            return switch (type.name().toString()) {
                case "int" -> GeneratedPropertyAccessor.KIND_INT;
                case "long" -> GeneratedPropertyAccessor.KIND_LONG;
                case "boolean" -> GeneratedPropertyAccessor.KIND_BOOLEAN;
                case "double" -> GeneratedPropertyAccessor.KIND_DOUBLE;
                default -> -1; // byte, short, char, float: left to Jackson's writer
            };
        }
        String typeName = type.name().toString();
        if ("java.lang.String".equals(typeName)) {
            return GeneratedPropertyAccessor.KIND_STRING;
        }
        if ("java.lang.Character".equals(typeName)) {
            return -1; // FieldSpecs reads it as a String
        }
        return GeneratedPropertyAccessor.KIND_OBJECT;
    }

    private static void createIndexOf(ClassCreator classCreator, List<Property> properties) {
        try (MethodCreator indexOf = classCreator.getMethodCreator("indexOf", int.class, String.class)
                .setModifiers(ACC_PUBLIC)) {
            AssignableResultHandle result = indexOf.createVariable(int.class);
            indexOf.assign(result, indexOf.load(-1));
            Switch.StringSwitch memberSwitch = indexOf.stringSwitch(indexOf.getMethodParam(0));
            for (int i = 0; i < properties.size(); i++) {
                final int index = i;
                memberSwitch.caseOf(properties.get(i).memberName(), bytecode -> bytecode.assign(result, bytecode.load(index)));
            }
            indexOf.returnValue(result);
        }
    }

    private static void createKindOf(ClassCreator classCreator, List<Property> properties) {
        try (MethodCreator kindOf = classCreator.getMethodCreator("kindOf", int.class, int.class).setModifiers(ACC_PUBLIC)) {
            AssignableResultHandle result = kindOf.createVariable(int.class);
            kindOf.assign(result, kindOf.load(-1));
            for (int i = 0; i < properties.size(); i++) {
                BytecodeCreator match = kindOf.ifIntegerEqual(kindOf.getMethodParam(0), kindOf.load(i)).trueBranch();
                match.assign(result, match.load(properties.get(i).kind()));
            }
            kindOf.returnValue(result);
        }
    }

    private static void createGetter(ClassCreator classCreator, String beanClassName, String name, Class<?> returnType,
            int kind, List<Property> properties) {
        boolean any = false;
        for (Property property : properties) {
            if (property.kind() == kind) {
                any = true;
                break;
            }
        }
        if (!any) {
            return; // the inherited implementation throws
        }
        try (MethodCreator getter = classCreator.getMethodCreator(name, returnType, Object.class, int.class)
                .setModifiers(ACC_PUBLIC)) {
            ResultHandle bean = getter.checkCast(getter.getMethodParam(0), beanClassName);
            ResultHandle index = getter.getMethodParam(1);
            for (int i = 0; i < properties.size(); i++) {
                Property property = properties.get(i);
                if (property.kind() != kind) {
                    continue;
                }
                BytecodeCreator match = getter.ifIntegerEqual(index, getter.load(i)).trueBranch();
                match.returnValue(property.fieldSpecs().toValueReaderHandle(match, bean));
            }
            getter.returnValue(getter.invokeSpecialMethod(
                    MethodDescriptor.ofMethod(GeneratedPropertyAccessor.class, name, returnType, Object.class, int.class),
                    getter.getThis(), getter.getMethodParam(0), index));
        }
    }
}
