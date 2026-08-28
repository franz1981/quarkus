package io.quarkus.resteasy.reactive.jackson.deployment.test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import jakarta.ws.rs.core.MediaType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusDevModeTest;

/**
 * The generated accessor belongs to the application it was generated for, while the class that registers it lives in
 * the extension runtime jar, which dev mode keeps across restarts. After a live reload the beans of the new
 * application must still be matched to the accessor generated for them.
 */
public class ReflectionFreeAccessorLiveReloadTest {

    @RegisterExtension
    static final QuarkusDevModeTest TEST = new QuarkusDevModeTest()
            .withApplicationRoot(jar -> jar.addClasses(LiveReloadResource.class, LiveReloadDto.class));

    @Test
    public void accessorIsStillUsedAfterLiveReload() {
        assertThat(version()).isEqualTo("v1");
        assertDeserializedThroughAccessor();

        // a source change forces a real restart: the application is augmented again and the accessor regenerated
        TEST.modifySourceFile(LiveReloadResource.class, source -> source.replace("\"v1\"", "\"v2\""));

        assertThat(version()).isEqualTo("v2");
        assertDeserializedThroughAccessor();
    }

    private static String version() {
        return given().get("/live-reload/version").then().statusCode(200).extract().body().asString();
    }

    private static void assertDeserializedThroughAccessor() {
        List<String> stackTrace = given()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body("{\"name\":\"whatever\"}")
                .post("/live-reload")
                .then()
                .statusCode(200)
                .extract().body().jsonPath().getList(".", String.class);

        assertThat(stackTrace).anyMatch(s -> s.contains("$quarkusjacksonaccessor."));
    }
}
