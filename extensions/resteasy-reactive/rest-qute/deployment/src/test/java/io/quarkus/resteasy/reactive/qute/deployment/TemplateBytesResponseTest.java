package io.quarkus.resteasy.reactive.qute.deployment;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import org.jboss.resteasy.reactive.server.ServerResponseFilter;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.resteasy.reactive.qute.runtime.Utf8Output;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.ExtractableResponse;
import io.smallrye.mutiny.Uni;

/**
 * A rendered template is written as UTF-8 bytes; endpoints with writer interceptors keep the String.
 */
public class TemplateBytesResponseTest {

    static final String CYR = "\u0422\u043E\u0432\u0430\u0440";
    static final String EMOJI = "\uD83D\uDE00";

    @RegisterExtension
    static final QuarkusExtensionTest config = new QuarkusExtensionTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(TestResource.class, Intercepted.class, EntityTypeInterceptor.class,
                            EntityFilter.class)
                    .addAsResource(new StringAsset("{#for i in items}<p>{i}</p>{/for}"), "templates/page.html")
                    .addAsResource(new StringAsset("{a}"), "templates/slow.html"))
            .overrideRuntimeConfigKey("quarkus.qute.timeout", "300");

    static List<String> items(int n) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            items.add((i % 2 == 0 ? CYR : "Product") + " " + i + " " + EMOJI + " <&>");
        }
        return items;
    }

    static String expected(int n) {
        StringBuilder sb = new StringBuilder();
        for (String i : items(n)) {
            sb.append("<p>").append(i.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")).append("</p>");
        }
        return sb.toString();
    }

    private static ExtractableResponse<?> get(String path) {
        return given().get(path).then().statusCode(200).extract();
    }

    /**
     * Same body and Content-Type as the same endpoint on the String path, plus a Content-Length.
     */
    private static void assertSameAsStringPath(String path, String stringPath) {
        for (int n : new int[] { 0, 1, 10, 3000 }) {
            ExtractableResponse<?> bytes = get(path + "?n=" + n);
            ExtractableResponse<?> string = get(stringPath + "?n=" + n);
            byte[] expected = expected(n).getBytes(StandardCharsets.UTF_8);
            assertThat(bytes.asByteArray()).isEqualTo(expected);
            assertThat(string.asByteArray()).isEqualTo(expected);
            assertThat(bytes.header("Content-Type")).isEqualTo(string.header("Content-Type"));
            assertThat(bytes.header("Content-Length")).isEqualTo(String.valueOf(expected.length));
            assertThat(bytes.header("X-Filter-Entity")).isEqualTo(Utf8Output.class.getName());
            assertThat(bytes.header("X-Filter-Length")).isEqualTo(String.valueOf(expected(n).length()));
            assertThat(string.header("X-Filter-Entity")).isEqualTo(String.class.getName());
        }
    }

    @Test
    public void testProduces() {
        assertSameAsStringPath("/test/page", "/test/intercepted");
    }

    @Test
    public void testUni() {
        assertSameAsStringPath("/test/uni", "/test/intercepted");
    }

    @Test
    public void testNoProduces() {
        assertSameAsStringPath("/test/no-produces", "/test/intercepted-no-produces");
    }

    @Test
    public void testResponseKeepsString() {
        ExtractableResponse<?> r = get("/test/response?n=10");
        assertThat(r.asByteArray()).isEqualTo(expected(10).getBytes(StandardCharsets.UTF_8));
        assertThat(r.header("X-Filter-Entity")).isEqualTo(String.class.getName());
    }

    @Test
    public void testNoMediaTypeKeepsString() {
        ExtractableResponse<?> r = get("/test/parsed");
        assertThat(r.asString()).isEqualTo(CYR);
        assertThat(r.header("Content-Type")).startsWith("text/plain");
        assertThat(r.header("X-Filter-Entity")).isEqualTo(String.class.getName());
    }

    @Test
    public void testWriterInterceptorGetsString() {
        ExtractableResponse<?> r = get("/test/intercepted?n=10");
        assertThat(r.header("X-Interceptor-Entity")).isEqualTo(String.class.getName());
    }

    @Test
    public void testTimeout() {
        given().get("/test/slow").then().statusCode(500);
    }

    @Path("test")
    public static class TestResource {

        @Inject
        Template page;

        @Inject
        Template slow;

        @Inject
        Engine engine;

        @GET
        @Path("page")
        @Produces("text/html;charset=UTF-8")
        public TemplateInstance page(@QueryParam("n") int n) {
            return page.data("items", items(n));
        }

        @GET
        @Path("uni")
        @Produces("text/html;charset=UTF-8")
        public Uni<TemplateInstance> uni(@QueryParam("n") int n) {
            return Uni.createFrom().item(page.data("items", items(n)));
        }

        @Intercepted
        @GET
        @Path("intercepted")
        @Produces("text/html;charset=UTF-8")
        public TemplateInstance intercepted(@QueryParam("n") int n) {
            return page.data("items", items(n));
        }

        @GET
        @Path("no-produces")
        public TemplateInstance noProduces(@QueryParam("n") int n) {
            return page.data("items", items(n));
        }

        @Intercepted
        @GET
        @Path("intercepted-no-produces")
        public TemplateInstance interceptedNoProduces(@QueryParam("n") int n) {
            return page.data("items", items(n));
        }

        @GET
        @Path("response")
        @Produces("text/html;charset=UTF-8")
        public Response response(@QueryParam("n") int n) {
            return Response.ok(page.data("items", items(n))).build();
        }

        @GET
        @Path("parsed")
        public TemplateInstance parsed() {
            return engine.parse("{a}").data("a", CYR);
        }

        @GET
        @Path("slow")
        @Produces("text/html;charset=UTF-8")
        public TemplateInstance slow() {
            return slow.data("a", new CompletableFuture<>());
        }
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Intercepted {
    }

    @Intercepted
    @Provider
    public static class EntityTypeInterceptor implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
            context.getHeaders().add("X-Interceptor-Entity", context.getEntity().getClass().getName());
            context.proceed();
        }
    }

    public static class EntityFilter {
        @ServerResponseFilter(priority = 1)
        public void filter(ContainerResponseContext response) {
            if (response.getEntity() instanceof CharSequence entity) {
                response.getHeaders().add("X-Filter-Entity", entity.getClass().getName());
                response.getHeaders().add("X-Filter-Length", entity.length());
            }
        }
    }
}
