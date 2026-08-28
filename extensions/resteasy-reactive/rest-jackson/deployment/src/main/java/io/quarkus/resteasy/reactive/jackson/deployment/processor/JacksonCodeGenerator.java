package io.quarkus.resteasy.reactive.jackson.deployment.processor;

import static io.quarkus.bootstrap.classloading.QuarkusClassLoader.isApplicationClass;

import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ArrayType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.MethodParameterInfo;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;
import org.jboss.jandex.TypeVariable;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.annotation.JacksonAnnotation;
import com.fasterxml.jackson.annotation.JacksonAnnotationsInside;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonView;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.gizmo.AssignableResultHandle;
import io.quarkus.gizmo.BranchResult;
import io.quarkus.gizmo.BytecodeCreator;
import io.quarkus.gizmo.FieldDescriptor;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.ResultHandle;

public abstract class JacksonCodeGenerator {

    protected static final Logger log = Logger.getLogger(JacksonCodeGenerator.class);

    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    private static final DotName KOTLIN_METADATA = DotName.createSimple("kotlin.Metadata");

    private static final Set<String> UNSUPPORTED_JAKARTA_PERSISTENCE_ANNOTATIONS = Set.of(
            "jakarta.persistence.Transient",
            "jakarta.persistence.Basic",
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToOne",
            "jakarta.persistence.OneToOne",
            "jakarta.persistence.ManyToMany",
            "jakarta.persistence.ElementCollection");

    private static final Set<String> SUPPORTED_JACKSON_ANNOTATIONS = Set.of(
            JacksonAnnotation.class.getName(),
            JacksonAnnotationsInside.class.getName(),
            JsonAlias.class.getName(),
            JsonAnyGetter.class.getName(),
            JsonAnySetter.class.getName(),
            JsonBackReference.class.getName(),
            JsonClassDescription.class.getName(),
            JsonCreator.class.getName(),
            JsonFormat.class.getName(),
            JsonGetter.class.getName(),
            JsonIgnore.class.getName(),
            JsonIgnoreProperties.class.getName(),
            JsonIgnoreType.class.getName(),
            JsonInclude.class.getName(),
            JsonManagedReference.class.getName(),
            JsonNaming.class.getName(),
            JsonProperty.class.getName(),
            JsonPropertyDescription.class.getName(),
            JsonPropertyOrder.class.getName(),
            JsonRawValue.class.getName(),
            JsonSetter.class.getName(),
            JsonSubTypes.class.getName(),
            JsonTypeInfo.class.getName(),
            JsonTypeName.class.getName(),
            JsonUnwrapped.class.getName(),
            JsonValue.class.getName(),
            JsonView.class.getName());

    protected final BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer;
    protected final IndexView jandexIndex;

    protected final Set<String> generatedClassNames = new HashSet<>();
    protected final Deque<ClassInfo> toBeGenerated = new ArrayDeque<>();

    protected JacksonCodeGenerator(BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer,
            IndexView jandexIndex) {
        this.generatedClassBuildItemBuildProducer = generatedClassBuildItemBuildProducer;
        this.jandexIndex = jandexIndex;
    }

    protected Collection<FieldInfo> classFields(ClassInfo classInfo) {
        Collection<FieldInfo> fields = new ArrayList<>();
        classFields(classInfo, fields);
        return fields;
    }

    protected void classFields(ClassInfo classInfo, Collection<FieldInfo> fields) {
        fields.addAll(classInfo.fields());
        onSuperClass(classInfo, superClassInfo -> {
            classFields(superClassInfo, fields);
            return null;
        });
    }

    protected <T> T onSuperClass(ClassInfo classInfo, Function<ClassInfo, T> f) {
        Type superType = classInfo.superClassType();
        if (superType != null && !vetoedClassName(superType.name().toString())) {
            ClassInfo superClassInfo = jandexIndex.getClassByName(superType.name());
            if (superClassInfo != null) {
                return f.apply(superClassInfo);
            }
        }
        return null;
    }

    protected Collection<MethodInfo> classMethods(ClassInfo classInfo) {
        Collection<MethodInfo> methods = new ArrayList<>();
        classMethods(classInfo, methods);
        return methods;
    }

    private void classMethods(ClassInfo classInfo, Collection<MethodInfo> methods) {
        methods.addAll(classInfo.methods());
        onSuperClass(classInfo, superClassInfo -> {
            classMethods(superClassInfo, methods);
            return null;
        });
    }

    protected MethodInfo findMethod(ClassInfo classInfo, String methodName, Type... parameters) {
        MethodInfo method = classInfo.method(methodName, parameters);
        return method != null ? method
                : onSuperClass(classInfo, superClassInfo -> findMethod(superClassInfo, methodName, parameters));
    }

    protected static String ucFirst(String name) {
        return name.substring(0, 1).toUpperCase() + name.substring(1);
    }

    protected static boolean vetoedClass(ClassInfo classInfo, String className) {
        return classInfo.isAbstract() || classInfo.isInterface() || vetoedClassName(className);
    }

    private static boolean vetoedClassName(String className) {
        return className.startsWith("java.") || className.startsWith("jakarta.")
                || className.startsWith("io.vertx.core.json.")
                || className.startsWith("com.fasterxml.jackson.databind.");
    }

    protected Optional<String> findUnknownAnnotation(ClassInfo classInfo) {
        Optional<String> unknown = classInfo.annotations().stream()
                .map(a -> a.name().toString())
                .filter(FieldSpecs::isUnknownAnnotation)
                .findFirst();
        if (unknown.isPresent()) {
            return unknown;
        }
        Optional<String> fromSuperClass = onSuperClass(classInfo, this::findUnknownAnnotation);
        return fromSuperClass != null ? fromSuperClass : Optional.empty();
    }

    protected static final DotName COLLECTION_NAME = DotName.createSimple(Collection.class);
    private static final DotName SET_NAME = DotName.createSimple(Set.class);
    protected static final DotName MAP_NAME = DotName.createSimple(Map.class);

    protected void registerTypeToBeGenerated(Type fieldType, String typeName) {
        if (fieldType instanceof TypeVariable) {
            return;
        }
        if (fieldType instanceof ArrayType aType) {
            registerTypeToBeGenerated(aType.constituent());
            return;
        }
        if (fieldType instanceof ParameterizedType pType) {
            if (pType.arguments().size() == 1) {
                if (isAssignableTo(typeName, SET_NAME)) {
                    registerTypeToBeGenerated(pType.arguments().get(0));
                    return;
                }
                if (typeName.equals("java.lang.Iterable") || isAssignableTo(typeName, COLLECTION_NAME)) {
                    registerTypeToBeGenerated(pType.arguments().get(0));
                    return;
                }
                registerTypeToBeGenerated(pType.arguments().get(0));
                return;
            }
            if (pType.arguments().size() == 2 && isAssignableTo(typeName, MAP_NAME)) {
                registerTypeToBeGenerated(pType.arguments().get(0));
                registerTypeToBeGenerated(pType.arguments().get(1));
                return;
            }
        }
        registerTypeToBeGenerated(typeName);
    }

    protected boolean isAssignableTo(String typeName, DotName targetName) {
        if (typeName.equals(targetName.toString())) {
            return true;
        }
        ClassInfo classInfo = jandexIndex.getClassByName(typeName);
        if (classInfo == null) {
            return false;
        }
        // check superclass
        if (classInfo.superName() != null && isAssignableTo(classInfo.superName().toString(), targetName)) {
            return true;
        }
        // check interfaces
        for (DotName iface : classInfo.interfaceNames()) {
            if (isAssignableTo(iface.toString(), targetName)) {
                return true;
            }
        }
        return false;
    }

    private void registerTypeToBeGenerated(Type type) {
        // a type argument can be parameterized itself, like the List<Foo> in a Map<String, List<Foo>>,
        // so recurse to reach the Foo nested in it
        if (type instanceof ParameterizedType pType) {
            for (Type argument : pType.arguments()) {
                registerTypeToBeGenerated(argument);
            }
        }
        registerTypeToBeGenerated(type.name().toString());
    }

    private void registerTypeToBeGenerated(String typeName) {
        ClassInfo classInfo = jandexIndex.getClassByName(typeName);
        if (classInfo == null || !isRuntimeAccessible(classInfo, typeName)) {
            return;
        }
        if (vetoedClass(classInfo, typeName)) {
            if (classInfo.isSealed()) {
                for (DotName subClassName : classInfo.permittedSubclasses()) {
                    registerTypeToBeGenerated(subClassName.toString());
                }
            }
            return;
        }
        if (shouldGenerateCodeFor(classInfo)) {
            toBeGenerated.add(classInfo);
        }
    }

    private static boolean isRuntimeAccessible(ClassInfo classInfo, String className) {
        return Modifier.isPublic(classInfo.flags()) || isApplicationClass(className);
    }

    protected boolean shouldGenerateCodeFor(ClassInfo classInfo) {
        return !classInfo.isEnum() && !classInfo.hasDeclaredAnnotation(KOTLIN_METADATA);
    }

    private static final DotName JSON_TYPE_INFO = DotName.createSimple(JsonTypeInfo.class);

    protected MethodInfo getterMethodInfo(ClassInfo classInfo, FieldInfo fieldInfo) {
        MethodInfo namedAccessor = findMethod(classInfo, fieldInfo.name());
        if (namedAccessor != null
                && (classInfo.isRecord() || namedAccessor.hasAnnotation(JsonProperty.class)
                        || fieldInfo.hasAnnotation(JsonProperty.class))) {
            return namedAccessor;
        }
        String methodName = (fieldInfo.type().name().toString().equals("boolean") ? "is" : "get") + ucFirst(fieldInfo.name());
        return findMethod(classInfo, methodName);
    }

    protected Optional<MethodInfo> findConstructor(ClassInfo classInfo) {
        Optional<MethodInfo> ctorOpt = classInfo.constructors().stream()
                .filter(ctor -> Modifier.isPublic(ctor.flags()) && ctor.hasAnnotation(JsonCreator.class))
                .findFirst();

        if (ctorOpt.isEmpty()) {
            if (classInfo.hasNoArgsConstructor() && !classInfo.isRecord()) {
                return classInfo.constructors().stream()
                        .filter(ctor -> ctor.parametersCount() == 0)
                        .findFirst();
            }
            ctorOpt = classInfo.isRecord() ? Optional.of(classInfo.canonicalRecordConstructor())
                    : classInfo.constructors().stream().filter(ctor -> Modifier.isPublic(ctor.flags())).findFirst();
        }
        return ctorOpt;
    }

    protected PropertyNamingStrategy getNamingStrategy(ClassInfo classInfo) {
        AnnotationInstance jsonNaming = classInfo.annotation(JsonNaming.class);
        if (jsonNaming == null || jsonNaming.value() == null) {
            return null;
        }
        String strategyClassName = jsonNaming.value().asClass().name().toString();
        try {
            return (PropertyNamingStrategy) Class.forName(strategyClassName)
                    .getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            return null;
        }
    }

    protected static String[] getPropertyOrder(ClassInfo classInfo) {
        AnnotationInstance ann = classInfo.declaredAnnotation(JsonPropertyOrder.class);
        if (ann == null || ann.value() == null) {
            return null;
        }
        return ann.value().asStringArray();
    }

    protected boolean isFieldTypeIgnored(FieldSpecs fieldSpecs) {
        ClassInfo typeInfo = jandexIndex.getClassByName(fieldSpecs.fieldType.name());
        return typeInfo != null && typeInfo.hasAnnotation(JsonIgnoreType.class);
    }

    protected FieldSpecs fieldSpecsFromField(ClassInfo classInfo, MethodInfo constructor, FieldInfo fieldInfo,
            PropertyNamingStrategy namingStrategy) {
        if (Modifier.isStatic(fieldInfo.flags())) {
            return null;
        }
        MethodInfo getterMethodInfo = getterMethodInfo(classInfo, fieldInfo);
        if (getterMethodInfo != null) {
            return new FieldSpecs(constructor, fieldInfo, getterMethodInfo, namingStrategy);
        }
        if (Modifier.isPublic(fieldInfo.flags())) {
            return new FieldSpecs(fieldInfo, namingStrategy);
        }
        return null;
    }

    protected static class FieldSpecs {

        final String fieldName;
        final String jsonName;
        final Type fieldType;

        private final Map<String, AnnotationInstance> annotations = new HashMap<>();

        MethodInfo methodInfo;
        FieldInfo fieldInfo;

        FieldSpecs(FieldInfo fieldInfo, PropertyNamingStrategy namingStrategy) {
            this(null, fieldInfo, null, namingStrategy);
        }

        FieldSpecs(MethodInfo constructor, FieldInfo fieldInfo, MethodInfo methodInfo, PropertyNamingStrategy namingStrategy) {
            if (fieldInfo != null) {
                this.fieldInfo = fieldInfo;
                readAnnotations(fieldInfo);
            }
            if (methodInfo != null) {
                this.methodInfo = methodInfo;
                readAnnotations(methodInfo);
            }
            this.fieldType = fieldType();
            this.fieldName = fieldName();
            this.jsonName = jsonName(constructor, namingStrategy);
        }

        FieldSpecs(ClassInfo classInfo, MethodParameterInfo paramInfo, PropertyNamingStrategy namingStrategy) {
            if (classInfo != null) {
                FieldInfo field = classInfo.field(paramInfo.name());
                if (field != null) {
                    this.fieldInfo = field;
                    readAnnotations(field);
                }
            }
            readAnnotations(paramInfo);
            this.fieldType = paramInfo.type();
            this.fieldName = paramInfo.name();
            this.jsonName = jsonName(null, namingStrategy);
        }

        private void readAnnotations(AnnotationTarget target) {
            target.annotations().forEach(a -> annotations.put(a.name().toString(), a));
        }

        public boolean isPublicField() {
            return fieldInfo != null && Modifier.isPublic(fieldInfo.flags());
        }

        private Type fieldType() {
            if (isPublicField()) {
                return fieldInfo.type();
            }
            if (methodInfo.parametersCount() == 1 && methodInfo.name().startsWith("set")) {
                return methodInfo.parameterType(0);
            }
            return methodInfo.returnType();
        }

        private String jsonName(MethodInfo constructor, PropertyNamingStrategy namingStrategy) {
            AnnotationInstance jsonProperty = annotations.get(JsonProperty.class.getName());
            if (jsonProperty == null) {
                jsonProperty = annotations.get(JsonGetter.class.getName());
            }
            if (jsonProperty == null) {
                jsonProperty = annotations.get(JsonSetter.class.getName());
            }
            if (jsonProperty == null && constructor != null) {
                jsonProperty = constructor.parameters().stream()
                        .filter(parameter -> parameter.name().equals(fieldName)).findFirst()
                        .map(parameter -> parameter.annotation(JsonProperty.class.getName()))
                        .orElse(null);
            }

            if (jsonProperty != null) {
                AnnotationValue value = jsonProperty.value();
                if (value != null && !value.asString().isEmpty()) {
                    return value.asString();
                }
            }
            if (namingStrategy != null) {
                return namingStrategy.nameForField(null, null, fieldName);
            }
            return fieldName;
        }

        private String fieldName() {
            return fieldInfo != null ? fieldInfo.name() : fieldNameFromMethod(methodInfo);
        }

        private String fieldNameFromMethod(MethodInfo methodInfo) {
            String methodName = methodInfo.name();
            if (methodName.equals("get") || methodName.equals("set") || methodName.equals("is")) {
                return methodName;
            }
            if (methodName.startsWith("is")) {
                return methodName.substring(2, 3).toLowerCase() + methodName.substring(3);
            }
            if (methodName.startsWith("get") || methodName.startsWith("set")) {
                return methodName.substring(3, 4).toLowerCase() + methodName.substring(4);
            }
            return methodName;
        }

        boolean isIgnoredField() {
            return annotations.get(JsonIgnore.class.getName()) != null
                    || annotations.get(java.beans.Transient.class.getName()) != null;
        }

        boolean isBackReference() {
            return annotations.get(JsonBackReference.class.getName()) != null;
        }

        static boolean isUnknownAnnotation(String ann) {
            if (ann.startsWith("com.fasterxml.jackson.")) {
                return !SUPPORTED_JACKSON_ANNOTATIONS.contains(ann);
            }
            return ann.startsWith("jakarta.persistence.") &&
                    UNSUPPORTED_JAKARTA_PERSISTENCE_ANNOTATIONS.contains(ann);
        }

        ResultHandle toValueReaderHandle(BytecodeCreator bytecode, ResultHandle valueHandle) {
            ResultHandle handle = accessorHandle(bytecode, valueHandle);

            return switch (fieldType.name().toString()) {
                case "char" -> bytecode.invokeStaticMethod(
                        MethodDescriptor.ofMethod(Character.class, "toString", String.class, char.class), handle);
                case "java.lang.Character" -> {
                    AssignableResultHandle result = bytecode.createVariable(String.class);
                    BranchResult nullCheck = bytecode.ifNull(handle);
                    nullCheck.trueBranch().assign(result, nullCheck.trueBranch().loadNull());
                    nullCheck.falseBranch().assign(result, nullCheck.falseBranch().invokeStaticMethod(
                            MethodDescriptor.ofMethod(Character.class, "toString", String.class, char.class), handle));
                    yield result;
                }
                default -> handle;
            };
        }

        private ResultHandle accessorHandle(BytecodeCreator bytecode, ResultHandle valueHandle) {
            if (methodInfo != null) {
                if (methodInfo.declaringClass().isInterface()) {
                    return bytecode.invokeInterfaceMethod(MethodDescriptor.of(methodInfo), valueHandle);
                }
                return bytecode.invokeVirtualMethod(MethodDescriptor.of(methodInfo), valueHandle);
            }
            return bytecode.readInstanceField(FieldDescriptor.of(fieldInfo), valueHandle);
        }

    }
}
