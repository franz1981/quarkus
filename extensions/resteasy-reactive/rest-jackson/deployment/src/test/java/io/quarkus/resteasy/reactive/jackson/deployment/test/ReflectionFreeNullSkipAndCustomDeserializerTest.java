package io.quarkus.resteasy.reactive.jackson.deployment.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import java.util.function.Supplier;

import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;

import io.quarkus.jackson.JsonMapperBuilderCustomizer;
import io.quarkus.resteasy.reactive.jackson.DisableSecureSerialization;
import io.quarkus.test.QuarkusExtensionTest;
import io.smallrye.common.annotation.NonBlocking;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * The generated property accessor only replaces the property access: null handling and a value deserializer the
 * application installed on the mapper must keep working exactly as they do without the optimization.
 */
public class ReflectionFreeNullSkipAndCustomDeserializerTest {

    @RegisterExtension
    static QuarkusExtensionTest test = new QuarkusExtensionTest()
            .setArchiveProducer(new Supplier<>() {
                @Override
                public JavaArchive get() {
                    return ShrinkWrap.create(JavaArchive.class)
                            .addClasses(TestResource.class, TestDto.class, TrimmingCustomizer.class);
                }
            });

    @Test
    public void nullIsSkippedForNullsSkipProperty() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"name\":null,\"nickname\":\"nick\",\"age\":null}")
                .post("/null-skip")
                .then()
                .statusCode(200)
                .body("name", is("default"))
                .body("age", is(42));
    }

    @Test
    public void customDeserializerRegisteredOnTheMapperIsUsed() {
        given().contentType(MediaType.APPLICATION_JSON)
                .body("{\"nickname\":\"  nick  \"}")
                .post("/null-skip")
                .then()
                .statusCode(200)
                .body("nickname", is("nick"));
    }

    @Path("/null-skip")
    @NonBlocking
    @DisableSecureSerialization
    public static class TestResource {

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.APPLICATION_JSON)
        public TestDto echo(TestDto dto) {
            return dto;
        }
    }

    public static class TestDto {

        private String name = "default";
        private String nickname;
        private int age = 42;

        public String getName() {
            return name;
        }

        @JsonSetter(nulls = Nulls.SKIP)
        public void setName(String name) {
            this.name = name;
        }

        public String getNickname() {
            return nickname;
        }

        public void setNickname(String nickname) {
            this.nickname = nickname;
        }

        public int getAge() {
            return age;
        }

        @JsonSetter(nulls = Nulls.SKIP)
        public void setAge(int age) {
            this.age = age;
        }
    }

    @Singleton
    public static class TrimmingCustomizer implements JsonMapperBuilderCustomizer {

        @Override
        public void customize(JsonMapper.Builder builder) {
            SimpleModule module = new SimpleModule();
            module.addDeserializer(String.class, new ValueDeserializer<String>() {
                @Override
                public String deserialize(JsonParser p, DeserializationContext ctxt) {
                    return p.getString().trim();
                }
            });
            builder.addModule(module);
        }
    }
}
