package io.quarkus.resteasy.reactive.jackson.deployment.processor;

import static org.objectweb.asm.Opcodes.ACC_FINAL;
import static org.objectweb.asm.Opcodes.ACC_PRIVATE;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;

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
import com.fasterxml.jackson.databind.PropertyNamingStrategy;

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

/**
 * Generates ONE accessor class for all the beans of the application. The class has a {@code private final int classId}; every
 * public getter dispatches on it to a
 * {@code private static} per-bean method holding the property switch, and one instance per bean (same class, different
 * classId) is registered. The call sites in the shared property writers therefore see a single receiver class and stay
 * monomorphic, at the price of the classId switch.
 *
 * <pre>{@code
 * public class UnifiedPropertyAccessor$quarkusjacksonaccessor extends GeneratedPropertyAccessor {
 *     private final int classId;
 *     public String stringGetter(Object bean, int index) {
 *         if (classId == 0) return stringGetter$0(bean, index);   // Person
 *         if (classId == 1) return stringGetter$1(bean, index);   // Address
 *         ...
 *         return super.stringGetter(bean, index);
 *     }
 *     private static String stringGetter$0(Object bean, int index) { Person p = (Person) bean; if (index == 3) return p.getFirstName(); ... }
 * }
 * }</pre>
 */
public class JacksonPropertyAccessorFactory extends JacksonCodeGenerator {

    public static final String CLASS_NAME = "io.quarkus.resteasy.reactive.jackson.runtime.generated.UnifiedPropertyAccessor$quarkusjacksonaccessor";

    /**
     * Everything is generated into a single class, whose constant pool cannot hold more than 65535 entries; each
     * property contributes roughly ten of them. Past this many properties the class is skipped entirely and Jackson
     * keeps using reflection, rather than failing the build with an ASM error.
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

    /** Bean class names in classId order, valid after {@link #create(Collection)}. */
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
        int propertyCount = 0;
        for (Bean bean : beans) {
            propertyCount += bean.properties().size();
        }
        if (propertyCount > MAX_PROPERTIES) {
            log.warnf("Not generating the reflection-free Jackson property accessor: the application has %d properties"
                    + " to generate access for and %d is the most that fits in one class. Jackson will access the"
                    + " properties of these %d classes reflectively.", propertyCount, MAX_PROPERTIES, beans.size());
            return List.of();
        }
        for (Bean bean : beans) {
            beanClassNames.add(bean.className());
        }
        generate(beans);
        return List.of(CLASS_NAME);
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

    private void generate(List<Bean> beans) {
        try (ClassCreator classCreator = new ClassCreator(
                new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true), CLASS_NAME, null,
                GeneratedPropertyAccessor.class.getName())) {
            FieldDescriptor classIdField = classCreator.getFieldCreator("classId", int.class)
                    .setModifiers(ACC_PRIVATE | ACC_FINAL).getFieldDescriptor();
            try (MethodCreator ctor = classCreator.getConstructorCreator(int.class, Class.class)) {
                ctor.invokeSpecialMethod(MethodDescriptor.ofConstructor(GeneratedPropertyAccessor.class, Class.class),
                        ctor.getThis(), ctor.getMethodParam(1));
                ctor.writeInstanceField(classIdField, ctor.getThis(), ctor.getMethodParam(0));
                ctor.returnVoid();
            }

            // per-bean static methods
            for (int id = 0; id < beans.size(); id++) {
                Bean bean = beans.get(id);
                createIndexOf(classCreator, id, bean);
                createIndexOfSetter(classCreator, id, bean);
                createKindOf(classCreator, id, bean);
                for (Getter getter : GETTERS) {
                    createGetter(classCreator, id, bean, getter);
                    createSetter(classCreator, id, bean, getter);
                }
            }

            // public dispatchers on classId
            try (MethodCreator indexOf = classCreator.getMethodCreator("indexOf", int.class, String.class)
                    .setModifiers(ACC_PUBLIC)) {
                ResultHandle classId = indexOf.readInstanceField(classIdField, indexOf.getThis());
                for (int id = 0; id < beans.size(); id++) {
                    BytecodeCreator match = indexOf.ifIntegerEqual(classId, indexOf.load(id)).trueBranch();
                    match.returnValue(match.invokeStaticMethod(
                            MethodDescriptor.ofMethod(CLASS_NAME, "indexOf$" + id, int.class, String.class),
                            indexOf.getMethodParam(0)));
                }
                indexOf.returnValue(indexOf.load(-1));
            }
            try (MethodCreator kindOf = classCreator.getMethodCreator("kindOf", int.class, int.class)
                    .setModifiers(ACC_PUBLIC)) {
                ResultHandle classId = kindOf.readInstanceField(classIdField, kindOf.getThis());
                for (int id = 0; id < beans.size(); id++) {
                    BytecodeCreator match = kindOf.ifIntegerEqual(classId, kindOf.load(id)).trueBranch();
                    match.returnValue(match.invokeStaticMethod(
                            MethodDescriptor.ofMethod(CLASS_NAME, "kindOf$" + id, int.class, int.class),
                            kindOf.getMethodParam(0)));
                }
                kindOf.returnValue(kindOf.load(-1));
            }
            try (MethodCreator indexOfSetter = classCreator.getMethodCreator("indexOfSetter", int.class, String.class)
                    .setModifiers(ACC_PUBLIC)) {
                ResultHandle classId = indexOfSetter.readInstanceField(classIdField, indexOfSetter.getThis());
                for (int id = 0; id < beans.size(); id++) {
                    BytecodeCreator match = indexOfSetter.ifIntegerEqual(classId, indexOfSetter.load(id)).trueBranch();
                    match.returnValue(match.invokeStaticMethod(
                            MethodDescriptor.ofMethod(CLASS_NAME, "indexOfSetter$" + id, int.class, String.class),
                            indexOfSetter.getMethodParam(0)));
                }
                indexOfSetter.returnValue(indexOfSetter.load(-1));
            }
            for (Getter getter : GETTERS) {
                List<Integer> beansWithSetter = new ArrayList<>();
                for (int id = 0; id < beans.size(); id++) {
                    if (hasSettableKind(beans.get(id), getter.kind())) {
                        beansWithSetter.add(id);
                    }
                }
                if (!beansWithSetter.isEmpty()) {
                    String setterName = getter.name().replace("Getter", "Setter");
                    try (MethodCreator method = classCreator
                            .getMethodCreator(setterName, void.class, Object.class, int.class, getter.returnType())
                            .setModifiers(ACC_PUBLIC)) {
                        ResultHandle classId = method.readInstanceField(classIdField, method.getThis());
                        for (int id : beansWithSetter) {
                            BytecodeCreator match = method.ifIntegerEqual(classId, method.load(id)).trueBranch();
                            match.invokeStaticMethod(
                                    MethodDescriptor.ofMethod(CLASS_NAME, setterName + "$" + id, void.class,
                                            Object.class, int.class, getter.returnType()),
                                    method.getMethodParam(0), method.getMethodParam(1), method.getMethodParam(2));
                            match.returnVoid();
                        }
                        method.invokeSpecialMethod(
                                MethodDescriptor.ofMethod(GeneratedPropertyAccessor.class, setterName, void.class,
                                        Object.class, int.class, getter.returnType()),
                                method.getThis(), method.getMethodParam(0), method.getMethodParam(1),
                                method.getMethodParam(2));
                        method.returnVoid();
                    }
                }
                List<Integer> beansWithKind = new ArrayList<>();
                for (int id = 0; id < beans.size(); id++) {
                    if (hasKind(beans.get(id), getter.kind())) {
                        beansWithKind.add(id);
                    }
                }
                if (beansWithKind.isEmpty()) {
                    continue;
                }
                try (MethodCreator method = classCreator
                        .getMethodCreator(getter.name(), getter.returnType(), Object.class, int.class)
                        .setModifiers(ACC_PUBLIC)) {
                    ResultHandle classId = method.readInstanceField(classIdField, method.getThis());
                    for (int id : beansWithKind) {
                        BytecodeCreator match = method.ifIntegerEqual(classId, method.load(id)).trueBranch();
                        match.returnValue(match.invokeStaticMethod(
                                MethodDescriptor.ofMethod(CLASS_NAME, getter.name() + "$" + id, getter.returnType(),
                                        Object.class, int.class),
                                method.getMethodParam(0), method.getMethodParam(1)));
                    }
                    method.returnValue(method.invokeSpecialMethod(
                            MethodDescriptor.ofMethod(GeneratedPropertyAccessor.class, getter.name(), getter.returnType(),
                                    Object.class, int.class),
                            method.getThis(), method.getMethodParam(0), method.getMethodParam(1)));
                }
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

    private static void createIndexOfSetter(ClassCreator classCreator, int id, Bean bean) {
        try (MethodCreator indexOf = classCreator.getMethodCreator("indexOfSetter$" + id, int.class, String.class)
                .setModifiers(ACC_PRIVATE | ACC_STATIC)) {
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

    private static void createSetter(ClassCreator classCreator, int id, Bean bean, Getter getter) {
        if (!hasSettableKind(bean, getter.kind())) {
            return;
        }
        String setterName = getter.name().replace("Getter", "Setter");
        try (MethodCreator method = classCreator
                .getMethodCreator(setterName + "$" + id, void.class, Object.class, int.class, getter.returnType())
                .setModifiers(ACC_PRIVATE | ACC_STATIC)) {
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

    private static void createIndexOf(ClassCreator classCreator, int id, Bean bean) {
        try (MethodCreator indexOf = classCreator.getMethodCreator("indexOf$" + id, int.class, String.class)
                .setModifiers(ACC_PRIVATE | ACC_STATIC)) {
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

    private static void createKindOf(ClassCreator classCreator, int id, Bean bean) {
        try (MethodCreator kindOf = classCreator.getMethodCreator("kindOf$" + id, int.class, int.class)
                .setModifiers(ACC_PRIVATE | ACC_STATIC)) {
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

    private static void createGetter(ClassCreator classCreator, int id, Bean bean, Getter getter) {
        if (!hasKind(bean, getter.kind())) {
            return;
        }
        try (MethodCreator method = classCreator
                .getMethodCreator(getter.name() + "$" + id, getter.returnType(), Object.class, int.class)
                .setModifiers(ACC_PRIVATE | ACC_STATIC)) {
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
