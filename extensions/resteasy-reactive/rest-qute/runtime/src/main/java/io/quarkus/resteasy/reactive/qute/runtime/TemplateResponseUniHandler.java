package io.quarkus.resteasy.reactive.qute.runtime;

import static io.quarkus.resteasy.reactive.qute.runtime.Util.*;

import jakarta.ws.rs.core.MediaType;

import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;

import io.quarkus.arc.Arc;
import io.quarkus.qute.Engine;
import io.quarkus.qute.TemplateInstance;
import io.smallrye.mutiny.Uni;

public class TemplateResponseUniHandler implements ServerRestHandler {

    private volatile Engine engine;

    @Override
    public void handle(ResteasyReactiveRequestContext requestContext) {
        Object result = requestContext.getResult();
        if (!(result instanceof TemplateInstance)) {
            return;
        }

        requestContext.requireCDIRequestScope();

        if (engine == null) {
            synchronized (this) {
                if (engine == null) {
                    engine = Arc.container().instance(Engine.class).get();
                }
            }
        }
        requestContext.setResult(createUni(requestContext, (TemplateInstance) result, engine));
    }

    private Uni<?> createUni(ResteasyReactiveRequestContext requestContext, TemplateInstance result, Engine engine) {
        MediaType mediaType = setSelectedVariant(result, requestContext.getRequest(),
                requestContext.getHttpHeaders().getAcceptableLanguages());
        requestContext.setResponseContentType(mediaType);
        if (useUtf8Output(mediaType, requestContext.getWriterInterceptors() != null)) {
            return toUtf8Uni(result, engine);
        }
        return toUni(result, engine);
    }

}
