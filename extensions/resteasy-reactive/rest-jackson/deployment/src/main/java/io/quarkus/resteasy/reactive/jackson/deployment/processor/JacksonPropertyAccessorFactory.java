package io.quarkus.resteasy.reactive.jackson.deployment.processor;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonGetter;

import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.gizmo.Switch;
import io.quarkus.resteasy.reactive.jackson.runtime.serialisers.GeneratedPropertyAccessor;
import tools.jackson.databind.PropertyNamingStrategy;

/**
 * Generates one accessor class per bean, named after it. Each class holds only its own property switch, so the
 * generated methods are reached directly with no class id and no dispatch, at the price of one class per bean and
 * megamorphic call sites in the shared property writers.
 *
 * <pre>{@code
 * public class Person$quarkusjacksonaccessor extends GeneratedPropertyAccessor {
 *     public int indexOf(String name) { switch (name) { case "firstName": return 3; ... } }
 *     public String stringGetter(Object bean, int index) {
 *         Person p = (Person) bean;
 *         if (index == 3) return p.getFirstName();
 *         ...
 *     }
 * }
 * }</pre>
 */
public class JacksonPropertyAccessorFactory extends JacksonCodeGenerator {

    private static final String CLASS_NAME_SUFFIX = "$quarkusjacksonaccessor";

    /**
     * A class's constant pool cannot hold more than 65535 entries and each property contributes roughly ten of them.
     * A bean past this many properties is skipped and Jackson keeps accessing it reflectively, rather than failing
     * the build with an ASM error.
     */
    private static final int MAX_PROPERTIES = 2000;

    private record Property(FieldSpecs fieldSpecs, String memberName, int kind, MethodInfo setter,
            FieldInfo settableField, String setterMemberName) {
    }

    private record Bean(String className, List<Property> properties) {
    }

    private final List<String> beanClassNames = new ArrayList<>();

    public JacksonPropertyAccessorFactory(BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer,
            IndexView jandexIndex) {
        super(generatedClassBuildItemBuildProducer, jandexIndex);
    }

    /** Bean class names, in the same order as the accessor class names returned by {@link #create(Collection)}. */
    public List<String> beanClassNames() {
        return beanClassNames;
    }

    public Collection<String> create(Collection<ClassInfo> classInfos) {
        for (ClassInfo classInfo : classInfos) {
            if (shouldGenerateCodeFor(classInfo)) {
                toBeGenerated.add(classInfo);
            }
        }
        List<Bean> beans = new ArrayList<>();
        while (!toBeGenerated.isEmpty()) {
            ClassInfo classInfo = toBeGenerated.removeFirst();
            String className = classInfo.name().toString();
            if (vetoedClass(classInfo, className) || !generatedClassNames.add(className)
                    || !shouldGenerateCodeFor(classInfo) || !Modifier.isPublic(classInfo.flags())) {
                continue;
            }
            Optional<String> unknownAnnotation = findUnknownAnnotation(classInfo);
            if (unknownAnnotation.isPresent()) {
                log.debugf("Skipping generation of reflection-free Jackson property accessor for class %s" +
                        " because it contains the unsupported Jackson annotation %s", className, unknownAnnotation.get());
                continue;
            }
            List<Property> properties = collectProperties(classInfo);
            if (!properties.isEmpty()) {
                beans.add(new Bean(className, properties));
            }
        }
        if (beans.isEmpty()) {
            return List.of();
        }
        List<String> created = new ArrayList<>(beans.size());
        for (Bean bean : beans) {
            if (bean.properties().size() > MAX_PROPERTIES) {
                log.warnf("Not generating the reflection-free Jackson property accessor for %s: it has %d properties"
                        + " to generate access for and %d is the most that fits in one class. Jackson will access its"
                        + " properties reflectively.", bean.className(), bean.properties().size(), MAX_PROPERTIES);
                continue;
            }
            String accessorClassName = bean.className() + CLASS_NAME_SUFFIX;
            generate(bean, accessorClassName);
            beanClassNames.add(bean.className());
            created.add(accessorClassName);
        }
        return created;
    }

    private List<Property> collectProperties(ClassInfo classInfo) {
        List<Property> properties = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (FieldSpecs fieldSpecs : collectAllFieldSpecs(classInfo, getNamingStrategy(classInfo))) {
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
            MethodInfo setter = setterMethod(classInfo, fieldSpecs);
            FieldInfo settableField = setter == null && fieldSpecs.isPublicField()
                    && !Modifier.isFinal(fieldSpecs.fieldInfo.flags()) ? fieldSpecs.fieldInfo : null;
            String setterMemberName = setter != null ? setter.name() : (settableField != null ? settableField.name() : null);
            properties.add(new Property(fieldSpecs, memberName, kind, setter, settableField, setterMemberName));
        }
        return properties;
    }

    private List<FieldSpecs> collectAllFieldSpecs(ClassInfo classInfo, PropertyNamingStrategy namingStrategy) {
        List<FieldSpecs> allSpecs = new ArrayList<>();
        MethodInfo constructor = findConstructor(classInfo).orElse(null);
        Set<MethodInfo> boundMethods = new HashSet<>();

        for (FieldInfo fieldInfo : classFields(classInfo)) {
            FieldSpecs fieldSpecs = fieldSpecsFromField(classInfo, constructor, fieldInfo, namingStrategy);
            if (fieldSpecs != null) {
                allSpecs.add(fieldSpecs);
                if (fieldSpecs.methodInfo != null) {
                    boundMethods.add(fieldSpecs.methodInfo);
                }
            }
        }

        for (MethodInfo methodInfo : classMethods(classInfo)) {
            if (boundMethods.contains(methodInfo)) {
                continue;
            }
            FieldSpecs fieldSpecs = fieldSpecsFromMethod(methodInfo, namingStrategy);
            if (fieldSpecs != null) {
                allSpecs.add(fieldSpecs);
            }
        }

        return sortByPropertyOrder(classInfo, allSpecs);
    }

    private static List<FieldSpecs> sortByPropertyOrder(ClassInfo classInfo, List<FieldSpecs> fieldSpecs) {
        // Sort fields according to @JsonPropertyOrder annotation if present
        String[] propertyOrder = getPropertyOrder(classInfo);
        if (propertyOrder == null) {
            return fieldSpecs;
        }

        List<String> orderList = Arrays.asList(propertyOrder);
        fieldSpecs.sort((a, b) -> {
            int idxA = orderList.indexOf(a.jsonName);
            int idxB = orderList.indexOf(b.jsonName);
            if (idxA == -1 && idxB == -1) {
                return 0;
            }
            if (idxA == -1) {
                return 1;
            }
            if (idxB == -1) {
                return -1;
            }
            return Integer.compare(idxA, idxB);
        });
        return fieldSpecs;
    }

    private FieldSpecs fieldSpecsFromMethod(MethodInfo methodInfo, PropertyNamingStrategy namingStrategy) {
        return !Modifier.isStatic(methodInfo.flags()) && isGetterMethod(methodInfo)
                ? new FieldSpecs(null, null, methodInfo, namingStrategy)
                : null;
    }

    private boolean isGetterMethod(MethodInfo methodInfo) {
        if (methodInfo.hasAnnotation(JsonAnyGetter.class)) {
            return false;
        }
        String methodName = methodInfo.name();
        return Modifier.isPublic(methodInfo.flags()) && !Modifier.isStatic(methodInfo.flags())
                && methodInfo.parametersCount() == 0 && methodInfo.returnType().kind() != Type.Kind.VOID
                && (methodName.startsWith("get") || methodName.startsWith("is")
                        || methodInfo.hasAnnotation(JsonGetter.class));
    }

    /**
     * The setter Jackson would otherwise call reflectively: {@code setFoo(T)} or {@code foo(T)}. A setter that returns
     * a value is left to Jackson, which uses what it returns ({@code SettableBeanProperty#deserializeSetAndReturn}).
     */
    private MethodInfo setterMethod(ClassInfo classInfo, FieldSpecs fieldSpecs) {
        if (fieldSpecs.fieldName == null) {
            return null;
        }
        MethodInfo setter = findMethod(classInfo, "set" + ucFirst(fieldSpecs.fieldName), fieldSpecs.fieldType);
        if (setter == null) {
            setter = findMethod(classInfo, fieldSpecs.fieldName, fieldSpecs.fieldType);
        }
        return setter != null && Modifier.isPublic(setter.flags()) && !Modifier.isStatic(setter.flags())
                && setter.returnType().kind() == Type.Kind.VOID ? setter : null;
    }

    private static int kindOf(Type type) {
        if (type.kind() == Type.Kind.PRIMITIVE) {
            return switch (type.name().toString()) {
                case "int" -> GeneratedPropertyAccessor.KIND_INT;
                case "long" -> GeneratedPropertyAccessor.KIND_LONG;
                case "boolean" -> GeneratedPropertyAccessor.KIND_BOOLEAN;
                case "double" -> GeneratedPropertyAccessor.KIND_DOUBLE;
                default -> -1;
            };
        }
        String typeName = type.name().toString();
        if ("java.lang.String".equals(typeName)) {
            return GeneratedPropertyAccessor.KIND_STRING;
        }
        if ("java.lang.Character".equals(typeName)) {
            return -1;
        }
        return GeneratedPropertyAccessor.KIND_OBJECT;
    }

    private record Getter(String name, Class<?> returnType, int kind) {
    }

    private static final Getter[] GETTERS = {
            new Getter("stringGetter", String.class, GeneratedPropertyAccessor.KIND_STRING),
            new Getter("intGetter", int.class, GeneratedPropertyAccessor.KIND_INT),
            new Getter("longGetter", long.class, GeneratedPropertyAccessor.KIND_LONG),
            new Getter("booleanGetter", boolean.class, GeneratedPropertyAccessor.KIND_BOOLEAN),
            new Getter("doubleGetter", double.class, GeneratedPropertyAccessor.KIND_DOUBLE),
            new Getter("objectGetter", Object.class, GeneratedPropertyAccessor.KIND_OBJECT) };

    private void generate(Bean bean, String accessorClassName) {
        try (ClassCreator classCreator = new ClassCreator(
                new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true), accessorClassName, null,
                GeneratedPropertyAccessor.class.getName())) {
            try (MethodCreator ctor = classCreator.getConstructorCreator(Class.class)) {
                ctor.invokeSpecialMethod(MethodDescriptor.ofConstructor(GeneratedPropertyAccessor.class, Class.class),
                        ctor.getThis(), ctor.getMethodParam(0));
                ctor.returnVoid();
            }
            createIndexOf(classCreator, bean);
            createIndexOfSetter(classCreator, bean);
            createKindOf(classCreator, bean);
            for (Getter getter : GETTERS) {
                createGetter(classCreator, bean, getter);
                createSetter(classCreator, bean, getter);
            }
        }
    }

    private static boolean hasSettableKind(Bean bean, int kind) {
        for (Property property : bean.properties()) {
            if (property.kind() == kind && property.setterMemberName() != null) {
                return true;
            }
        }
        return false;
    }

    private static void createIndexOfSetter(ClassCreator classCreator, Bean bean) {
        try (MethodCreator indexOf = classCreator.getMethodCreator("indexOfSetter", int.class, String.class)
                .setModifiers(ACC_PUBLIC)) {
            AssignableResultHandle result = indexOf.createVariable(int.class);
            indexOf.assign(result, indexOf.load(-1));
            Switch.StringSwitch memberSwitch = indexOf.stringSwitch(indexOf.getMethodParam(0));
            List<Property> properties = bean.properties();
            for (int i = 0; i < properties.size(); i++) {
                String setterMember = properties.get(i).setterMemberName();
                if (setterMember == null) {
                    continue;
                }
                final int index = i;
                memberSwitch.caseOf(setterMember, bytecode -> bytecode.assign(result, bytecode.load(index)));
            }
            indexOf.returnValue(result);
        }
    }

    private static void createSetter(ClassCreator classCreator, Bean bean, Getter getter) {
        if (!hasSettableKind(bean, getter.kind())) {
            return;
        }
        String setterName = getter.name().replace("Getter", "Setter");
        try (MethodCreator method = classCreator
                .getMethodCreator(setterName, void.class, Object.class, int.class, getter.returnType())
                .setModifiers(ACC_PUBLIC)) {
            ResultHandle beanHandle = method.checkCast(method.getMethodParam(0), bean.className());
            ResultHandle index = method.getMethodParam(1);
            ResultHandle value = method.getMethodParam(2);
            List<Property> properties = bean.properties();
            for (int i = 0; i < properties.size(); i++) {
                Property property = properties.get(i);
                if (property.kind() != getter.kind() || property.setterMemberName() == null) {
                    continue;
                }
                BytecodeCreator match = method.ifIntegerEqual(index, method.load(i)).trueBranch();
                ResultHandle argument = property.kind() == GeneratedPropertyAccessor.KIND_OBJECT
                        ? match.checkCast(value, property.fieldSpecs().fieldType.name().toString())
                        : value;
                if (property.setter() != null) {
                    if (property.setter().declaringClass().isInterface()) {
                        match.invokeInterfaceMethod(property.setter(), beanHandle, argument);
                    } else {
                        match.invokeVirtualMethod(property.setter(), beanHandle, argument);
                    }
                } else {
                    match.writeInstanceField(FieldDescriptor.of(property.settableField()), beanHandle, argument);
                }
                match.returnVoid();
            }
            method.throwException(IllegalStateException.class,
                    "No generated setter for the property of " + bean.className());
        }
    }

    private static boolean hasKind(Bean bean, int kind) {
        for (Property property : bean.properties()) {
            if (property.kind() == kind) {
                return true;
            }
        }
        return false;
    }

    private static void createIndexOf(ClassCreator classCreator, Bean bean) {
        try (MethodCreator indexOf = classCreator.getMethodCreator("indexOf", int.class, String.class)
                .setModifiers(ACC_PUBLIC)) {
            AssignableResultHandle result = indexOf.createVariable(int.class);
            indexOf.assign(result, indexOf.load(-1));
            Switch.StringSwitch memberSwitch = indexOf.stringSwitch(indexOf.getMethodParam(0));
            List<Property> properties = bean.properties();
            for (int i = 0; i < properties.size(); i++) {
                final int index = i;
                memberSwitch.caseOf(properties.get(i).memberName(), bytecode -> bytecode.assign(result, bytecode.load(index)));
            }
            indexOf.returnValue(result);
        }
    }

    private static void createKindOf(ClassCreator classCreator, Bean bean) {
        try (MethodCreator kindOf = classCreator.getMethodCreator("kindOf", int.class, int.class)
                .setModifiers(ACC_PUBLIC)) {
            AssignableResultHandle result = kindOf.createVariable(int.class);
            kindOf.assign(result, kindOf.load(-1));
            List<Property> properties = bean.properties();
            for (int i = 0; i < properties.size(); i++) {
                BytecodeCreator match = kindOf.ifIntegerEqual(kindOf.getMethodParam(0), kindOf.load(i)).trueBranch();
                match.assign(result, match.load(properties.get(i).kind()));
            }
            kindOf.returnValue(result);
        }
    }

    private static void createGetter(ClassCreator classCreator, Bean bean, Getter getter) {
        if (!hasKind(bean, getter.kind())) {
            return;
        }
        try (MethodCreator method = classCreator
                .getMethodCreator(getter.name(), getter.returnType(), Object.class, int.class)
                .setModifiers(ACC_PUBLIC)) {
            ResultHandle beanHandle = method.checkCast(method.getMethodParam(0), bean.className());
            ResultHandle index = method.getMethodParam(1);
            List<Property> properties = bean.properties();
            for (int i = 0; i < properties.size(); i++) {
                Property property = properties.get(i);
                if (property.kind() != getter.kind()) {
                    continue;
                }
                BytecodeCreator match = method.ifIntegerEqual(index, method.load(i)).trueBranch();
                match.returnValue(property.fieldSpecs().toValueReaderHandle(match, beanHandle));
            }
            method.throwException(IllegalStateException.class,
                    "No generated accessor for the property of " + bean.className());
        }
    }
}
