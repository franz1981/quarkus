package io.quarkus.rest.client.reactive.jackson.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.mutiny.Uni;

/**
 * With {@code quarkus.rest.jackson.optimization.enable-reflection-free-serializers} the reflection-free Jackson
 * (de)serializers are generated also for the entities of the REST Client interfaces, which are read and written through
 * the same {@code ObjectMapper} as the server endpoints. Here the server endpoints only consume and produce strings, so
 * they do not trigger the generation for any of those entities.
 */
public class ReflectionFreeSerializersForRestClientTest {

    @RegisterExtension
    static final QuarkusExtensionTest TEST = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Element.class, ElementTable.class, Order.class, Source.class,
                    SourceClient.class))
            .overrideConfigKey("quarkus.rest.jackson.optimization.enable-reflection-free-serializers", "true")
            .overrideConfigKey("quarkus.rest-client.source.url", "http://localhost:${quarkus.http.test-port:8081}");

    @RestClient
    SourceClient client;

    @Test
    void responseIsReadByGeneratedDeserializer() {
        ElementTable table = client.elements().await().indefinitely();
        assertThat(table.elements).containsOnlyKeys("H", "He");
        assertThat(table.elements.get("He").name).isEqualTo("Helium");
        assertThat(table.elements.get("He").number).isEqualTo(2);
        assertThat(table.putCallers).containsOnly(ElementTable.class.getName() + "$quarkusjacksondeserializer");
    }

    @Test
    void requestIsWrittenByGeneratedSerializer() {
        Order order = new Order();
        order.item = "book";
        assertThat(client.order(order)).isEqualTo("{\"item\":\"book\"}");
        assertThat(order.getterCallers).containsOnly(Order.class.getName() + "$quarkusjacksonserializer");
    }

    private static String caller() {
        return StackWalker.getInstance().walk(frames -> frames.skip(2).findFirst().orElseThrow().getClassName());
    }

    public static class Element {
        public String name;
        public int number;
    }

    public static class ElementTable {
        final Map<String, Element> elements = new LinkedHashMap<>();
        final Set<String> putCallers = new HashSet<>();

        @JsonAnySetter
        public void put(String symbol, Element element) {
            putCallers.add(caller());
            elements.put(symbol, element);
        }
    }

    public static class Order {
        String item;
        final Set<String> getterCallers = new HashSet<>();

        public String getItem() {
            getterCallers.add(caller());
            return item;
        }

        public void setItem(String item) {
            this.item = item;
        }
    }

    @Path("/source")
    public static class Source {

        @GET
        @Path("elements")
        @Produces(MediaType.APPLICATION_JSON)
        public String elements() {
            return "{\"H\": {\"name\": \"Hydrogen\", \"number\": 1}, \"He\": {\"name\": \"Helium\", \"number\": 2}}";
        }

        @POST
        @Path("order")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        public String order(String json) {
            return json;
        }
    }

    @Path("/source")
    @RegisterRestClient(configKey = "source")
    public interface SourceClient {

        @GET
        @Path("elements")
        @Produces(MediaType.APPLICATION_JSON)
        Uni<ElementTable> elements();

        @POST
        @Path("order")
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        String order(Order order);
    }
}
