package io.quarkus.resteasy.reactive.jackson.deployment.test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.resteasy.reactive.jackson.DisableSecureSerialization;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.NonBlocking;

public class DefaultReflectionFreeDeserializerTest {

    @RegisterExtension
    static QuarkusExtensionTest test = new QuarkusExtensionTest()
            .setArchiveProducer(new Supplier<>() {
                @Override
                public JavaArchive get() {
                    return ShrinkWrap.create(JavaArchive.class)
                            .addClasses(TestResource.class, TestDto.class);
                }
            }).overrideConfigKey("quarkus.rest.jackson.optimization.enable-reflection-free-serializers", "true");

    @Test
    public void test() {
        List<String> stackTrace = given()
                .contentType("application/json")
                .accept("application/json")
                .body("{\"name\":\"whatever\"}")
                .post("/test")
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getList(".", String.class);

        assertThat(stackTrace).anyMatch(s -> s.contains("$quarkusjacksonaccessor."))
                .noneMatch(s -> s.contains("MethodProperty.deserializeAndSet"));
    }

    @Path("/test")
    @NonBlocking
    @DisableSecureSerialization
    public static class TestResource {

        @POST
        public List<String> post(TestDto dto) {
            return dto.getStackTrace();
        }
    }

    public static class TestDto {

        private String name;
        private List<String> stackTrace;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
            this.stackTrace = computeStackTrace();
        }

        public List<String> getStackTrace() {
            return stackTrace;
        }

        private static List<String> computeStackTrace() {
            return StackWalker.getInstance()
                    .walk(frames -> frames.limit(10)
                            .map(frame -> frame.getClassName() + "." + frame.getMethodName())
                            .collect(Collectors.toList()));
        }
    }
}
