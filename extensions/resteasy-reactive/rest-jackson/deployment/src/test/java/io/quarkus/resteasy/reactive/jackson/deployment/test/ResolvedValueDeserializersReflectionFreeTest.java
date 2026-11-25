package io.quarkus.resteasy.reactive.jackson.deployment.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.RestAssured;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * The generated streaming deserializers read the values of the non-primitive properties and of the {@code @JsonAnySetter}
 * methods through deserializers that they look up once, when Jackson resolves them, instead of calling
 * {@code DeserializationContext#readValue(JsonParser, Class)} for each value, which looks up and contextualizes the
 * deserializer of the value every time.
 */
public class ResolvedValueDeserializersReflectionFreeTest {

    @RegisterExtension
    static QuarkusExtensionTest test = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Element.class, ElementTable.class, ShellsTable.class,
                    Counted.class, CountingDeserializer.class, CountedTable.class, CountedHolder.class,
                    Category.class, UntypedTable.class, TableResource.class))
            .overrideConfigKey("quarkus.rest.jackson.optimization.enable-reflection-free-serializers", "true");

    @Test
    void anySetterValueTypeHasGeneratedDeserializer() {
        // Element is only the value type of the @JsonAnySetter method of ElementTable, the body of an endpoint
        RestAssured.get("/tables/deserializer/element")
                .then()
                .statusCode(200)
                .body(endsWith("Element$quarkusjacksondeserializer"));
    }

    @Test
    void anySetterValues() {
        post("elements", "{\"H\": {\"name\": \"Hydrogen\", \"number\": 1, \"group\": 1},"
                + " \"He\": {\"name\": \"Helium\", \"number\": 2, \"group\": 18}, \"X\": null}")
                .body(is("H=Hydrogen/1/1,He=Helium/2/18,X=null"));
        post("shells", "{\"H\": [1], \"Li\": [2, 1], \"X\": null}")
                .body(is("H=[1],Li=[2, 1],X=null"));
        post("untyped", "{\"a\": 1, \"b\": [\"x\"], \"c\": {\"d\": true}, \"e\": null}")
                .body(is("{a=1, b=[x], c={d=true}, e=null}"));
    }

    @Test
    void cyclicTypes() {
        // Category refers to itself through both a property and its @JsonAnySetter method
        post("categories", "{\"name\": \"root\", \"parent\": {\"name\": \"up\"},"
                + " \"a\": {\"name\": \"a\", \"b\": {\"name\": \"b\"}}}")
                .body(is("root^up[a[b]]"));
    }

    @Test
    void valueDeserializersAreContextualizedOnlyWhenResolved() {
        // warm up: the deserializers of the tables are created and resolved on the first read
        post("counted", table(3)).body(is("3"));
        post("counted-holder", "{\"counted\": {\"value\": 1}}").body(is("1"));

        int before = CountingDeserializer.CONTEXTUALIZATIONS.get();
        post("counted", table(100)).body(is("100"));
        post("counted-holder", "{\"counted\": {\"value\": 1}}").body(is("1"));
        // before, DeserializationContext#readValue contextualized the deserializer of Counted for each value
        assertThat(CountingDeserializer.CONTEXTUALIZATIONS.get() - before).isZero();
    }

    @Test
    void eachObjectMapperResolvesItsOwnValueDeserializers() {
        String json = "{\"H\": {\"name\": \"Hydrogen\", \"number\": 1, \"group\": 1}}";
        // the default ObjectMapper, then one also having a custom deserializer for Element, then the default one again:
        // a single instance of the generated deserializer of ElementTable would keep the deserializer of Element
        // resolved through the last of them
        post("elements", json).body(is("H=Hydrogen/1/1"));
        post("elements-custom-mapper", json).body(is("H=custom"));
        post("elements", json).body(is("H=Hydrogen/1/1"));
    }

    private static String table(int size) {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < size; i++) {
            json.append(i == 0 ? "" : ",").append("\"k").append(i).append("\": {\"value\": 1}");
        }
        return json.append('}').toString();
    }

    private static io.restassured.response.ValidatableResponse post(String path, String body) {
        return RestAssured.given()
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .when().post("/tables/" + path)
                .then()
                .statusCode(200);
    }

    public static class Element {
        public String name;
        public int number;
        public int group;
    }

    public static class ElementTable {
        final Map<String, Element> elements = new LinkedHashMap<>();

        @JsonAnySetter
        public void put(String symbol, Element element) {
            elements.put(symbol, element);
        }
    }

    public static class ShellsTable {
        final Map<String, int[]> shells = new LinkedHashMap<>();

        @JsonAnySetter
        public void put(String symbol, int[] shellsOfSymbol) {
            shells.put(symbol, shellsOfSymbol);
        }
    }

    @JsonDeserialize(using = CountingDeserializer.class)
    public static class Counted {
        public int value;
    }

    public static class CountingDeserializer extends StdDeserializer<Counted> {
        static final AtomicInteger CONTEXTUALIZATIONS = new AtomicInteger();

        public CountingDeserializer() {
            super(Counted.class);
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext ctxt, BeanProperty property) {
            CONTEXTUALIZATIONS.incrementAndGet();
            return this;
        }

        @Override
        public Counted deserialize(JsonParser p, DeserializationContext ctxt) {
            Counted counted = new Counted();
            counted.value = ctxt.readTree(p).get("value").asInt();
            return counted;
        }
    }

    public static class CountedTable {
        int sum;

        @JsonAnySetter
        public void put(String key, Counted counted) {
            sum += counted.value;
        }
    }

    public static class CountedHolder {
        public Counted counted;
    }

    public static class Category {
        public String name;
        public Category parent;
        final List<Category> children = new ArrayList<>();

        @JsonAnySetter
        public void put(String key, Category child) {
            children.add(child);
        }

        @Override
        public String toString() {
            return name + (parent == null ? "" : "^" + parent) + (children.isEmpty() ? "" : children);
        }
    }

    public static class UntypedTable {
        @JsonAnySetter
        public final Map<String, Object> values = new LinkedHashMap<>();
    }

    @Path("/tables")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.TEXT_PLAIN)
    public static class TableResource {

        @Inject
        ObjectMapper objectMapper;

        @POST
        @Path("elements")
        public String elements(ElementTable table) {
            List<String> entries = new ArrayList<>();
            table.elements.forEach((symbol, e) -> entries.add(symbol + "="
                    + (e == null ? "null" : e.name + "/" + e.number + "/" + e.group)));
            return String.join(",", entries);
        }

        @POST
        @Path("elements-custom-mapper")
        public String elementsWithCustomMapper(String json) {
            ObjectMapper custom = objectMapper.rebuild()
                    .addModule(new SimpleModule().addDeserializer(Element.class, new StdDeserializer<>(Element.class) {
                        @Override
                        public Element deserialize(JsonParser p, DeserializationContext ctxt) {
                            ctxt.readTree(p);
                            Element element = new Element();
                            element.name = "custom";
                            return element;
                        }
                    }))
                    .build();
            ElementTable table = custom.readValue(json, ElementTable.class);
            List<String> entries = new ArrayList<>();
            table.elements.forEach((symbol, e) -> entries.add(symbol + "=" + e.name));
            return String.join(",", entries);
        }

        @POST
        @Path("shells")
        public String shells(ShellsTable table) {
            List<String> entries = new ArrayList<>();
            table.shells.forEach((symbol, s) -> entries.add(symbol + "=" + (s == null ? "null" : Arrays.toString(s))));
            return String.join(",", entries);
        }

        @POST
        @Path("untyped")
        public String untyped(UntypedTable table) {
            return table.values.toString();
        }

        @POST
        @Path("counted")
        public String counted(CountedTable table) {
            return String.valueOf(table.sum);
        }

        @POST
        @Path("counted-holder")
        public String countedHolder(CountedHolder holder) {
            return String.valueOf(holder.counted.value);
        }

        @POST
        @Path("categories")
        public String categories(Category category) {
            return category.toString();
        }

        @GET
        @Path("deserializer/{type}")
        public String deserializer(@PathParam("type") String type) {
            Class<?> clazz = switch (type) {
                case "element" -> Element.class;
                default -> throw new IllegalArgumentException(type);
            };
            return objectMapper._deserializationContext()
                    .findRootValueDeserializer(objectMapper.constructType(clazz))
                    .getClass().getName();
        }
    }
}
