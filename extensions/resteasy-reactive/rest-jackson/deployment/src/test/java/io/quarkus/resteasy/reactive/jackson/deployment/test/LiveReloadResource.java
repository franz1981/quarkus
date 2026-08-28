package io.quarkus.resteasy.reactive.jackson.deployment.test;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.quarkus.resteasy.reactive.jackson.DisableSecureSerialization;
import io.smallrye.common.annotation.NonBlocking;

@Path("/live-reload")
@NonBlocking
@DisableSecureSerialization
public class LiveReloadResource {

    @GET
    @Path("version")
    @Produces(MediaType.TEXT_PLAIN)
    public String version() {
        return "v1";
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public List<String> post(LiveReloadDto dto) {
        return dto.getStackTrace();
    }
}
