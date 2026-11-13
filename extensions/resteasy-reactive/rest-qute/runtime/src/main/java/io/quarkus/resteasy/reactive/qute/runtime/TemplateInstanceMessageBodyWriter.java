package io.quarkus.resteasy.reactive.qute.runtime;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;

import org.jboss.resteasy.reactive.server.providers.serialisers.ServerStringMessageBodyHandler;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveResourceInfo;
import org.jboss.resteasy.reactive.server.spi.ServerMessageBodyWriter;
import org.jboss.resteasy.reactive.server.spi.ServerRequestContext;
import org.jboss.resteasy.reactive.server.vertx.VertxResteasyReactiveRequestContext;

import io.netty.buffer.ByteBuf;
import io.quarkus.vertx.utils.NoBoundChecksBuffer;
import io.vertx.core.impl.buffer.VertxByteBufAllocator;

/**
 * Writes the rendered result of endpoints returning a {@link io.quarkus.qute.TemplateInstance}: a {@link Utf8Output} as
 * one direct buffer, or a {@link String} with the String writer of RESTEasy Reactive.
 */
public class TemplateInstanceMessageBodyWriter implements ServerMessageBodyWriter<Object> {

    private final ServerStringMessageBodyHandler strings = new ServerStringMessageBodyHandler();

    private static boolean isRendered(Class<?> type) {
        return type == Utf8Output.class || type == String.class;
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, ResteasyReactiveResourceInfo target, MediaType mediaType) {
        return isRendered(type);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isRendered(type);
    }

    @Override
    public void writeResponse(Object entity, Type genericType, ServerRequestContext context)
            throws WebApplicationException {
        if (!(entity instanceof Utf8Output output)) {
            strings.writeResponse(entity, genericType, context);
            return;
        }
        if (context.serverResponse() instanceof VertxResteasyReactiveRequestContext vertxContext) {
            vertxContext.end(new NoBoundChecksBuffer(toDirectByteBuf(output)));
        } else {
            context.serverResponse().end(output.toByteArray());
        }
    }

    @Override
    public void writeTo(Object entity, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException {
        if (!(entity instanceof Utf8Output output)) {
            strings.writeTo(entity, type, genericType, annotations, mediaType, httpHeaders, entityStream);
            return;
        }
        for (int i = 0; i < output.chunkCount(); i++) {
            entityStream.write(output.chunk(i), 0, output.chunkLength(i));
        }
    }

    private static ByteBuf toDirectByteBuf(Utf8Output output) {
        int length = output.byteLength();
        ByteBuf buf = VertxByteBufAllocator.POOLED_ALLOCATOR.directBuffer(length, length);
        try {
            for (int i = 0; i < output.chunkCount(); i++) {
                buf.writeBytes(output.chunk(i), 0, output.chunkLength(i));
            }
        } catch (Throwable t) {
            buf.release();
            throw t;
        }
        return buf;
    }
}
