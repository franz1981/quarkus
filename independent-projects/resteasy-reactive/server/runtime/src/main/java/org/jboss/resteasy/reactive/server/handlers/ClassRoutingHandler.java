package org.jboss.resteasy.reactive.server.handlers;

import static org.jboss.resteasy.reactive.server.handlers.ClassRoutingHandler.RoutingMappers.allHttpMethods;
import static org.jboss.resteasy.reactive.server.handlers.ClassRoutingHandler.RoutingMappers.isAllHttpMethods;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.common.headers.MediaTypeHeaderDelegate;
import org.jboss.resteasy.reactive.common.util.MediaTypeHelper;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.jaxrs.ResponseBuilderImpl;
import org.jboss.resteasy.reactive.server.mapping.RequestMapper;
import org.jboss.resteasy.reactive.server.mapping.RuntimeResource;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;

@SuppressWarnings("ForLoopReplaceableByForEach")
public class ClassRoutingHandler implements ServerRestHandler {
    private static final String INVALID_ACCEPT_HEADER_MESSAGE = "The accept header value did not match the value in @Produces";

    private final RoutingMappers mappers;
    private final int parameterOffset;
    final boolean resumeOn404;

    public ClassRoutingHandler(RoutingMappers mappers, int parameterOffset, boolean resumeOn404) {
        this.mappers = mappers;
        this.parameterOffset = parameterOffset;
        this.resumeOn404 = resumeOn404;
    }

    @Override
    public void handle(ResteasyReactiveRequestContext requestContext) throws Exception {
        RequestMapper<RuntimeResource> mapper = mappers.get(requestContext.getMethod());
        if (mapper == null) {
            String requestMethod = requestContext.getMethod();
            if (requestMethod.equals(HttpMethod.HEAD)) {
                mapper = mappers.get(HttpMethod.GET);
            } else if (requestMethod.equals(HttpMethod.OPTIONS)) {
                Set<String> allowedMethods = new HashSet<>();
                for (String method : mappers.mutableHttpMethods()) {
                    if (isAllHttpMethods(method)) {
                        continue;
                    }
                    allowedMethods.add(method);
                }
                allowedMethods.add(HttpMethod.OPTIONS);
                allowedMethods.add(HttpMethod.HEAD);
                requestContext.abortWith(Response.ok().allow(allowedMethods).build());
                return;
            }
            if (mapper == null) {
                mapper = mappers.get(allHttpMethods());
            }
            if (mapper == null) {
                // The idea here is to check if any of the mappers of the class could map the request - if the HTTP Method were correct
                String remaining = getRemaining(requestContext);
                for (RequestMapper<RuntimeResource> existingMapper : mappers.mutableRequestMappers()) {
                    if (existingMapper.map(remaining) != null) {
                        throw new NotAllowedException(
                                new ResponseBuilderImpl().status(Response.Status.METHOD_NOT_ALLOWED).build());
                    }
                }
                throwNotFound(requestContext);
                return;
            }
        }
        String remaining = getRemaining(requestContext);
        RequestMapper.RequestMatch<RuntimeResource> target = mapper.map(remaining);
        if (target == null) {
            if (requestContext.getMethod().equals(HttpMethod.HEAD)) {
                mapper = mappers.get(HttpMethod.GET);
                if (mapper != null) {
                    target = mapper.map(remaining);
                }
            }

            if (target == null) {
                // The idea here is to check if any of the mappers of the class could map the request - if the HTTP Method were correct
                for (Map.Entry<String, RequestMapper<RuntimeResource>> entry : mappers.mutableEntrySet()) {
                    if (isAllHttpMethods(entry.getKey())) {
                        continue;
                    }
                    if (entry.getKey().equals(requestContext.getMethod())) {
                        continue;
                    }
                    if (entry.getValue().map(remaining) != null) {
                        throw new NotAllowedException(
                                new ResponseBuilderImpl().status(Response.Status.METHOD_NOT_ALLOWED).build());
                    }
                }
                throwNotFound(requestContext);
                return;
            }
        }

        // according to the spec we need to return HTTP 415 when content-type header doesn't match what is specified in @Consumes
        if (!target.value.getConsumes().isEmpty()) {
            var contentTypes = requestContext.getContentType(true);
            if (contentTypes != null) {
                try {
                    if (MediaTypeHelper.getFirstMatch(
                            target.value.getConsumes(),
                            Collections.singletonList(MediaType.valueOf(contentTypes.get(0)))) == null) {
                        throw new NotSupportedException("The content-type header value did not match the value in @Consumes");
                    }
                } catch (IllegalArgumentException e) {
                    throw new NotSupportedException("The content-type header value did not correspond to a valid media type");
                }
            }
        }
        // according to the spec we need to return HTTP 406 when Accept header doesn't match what is specified in @Produces
        if (target.value.getProduces() != null) {
            // there could potentially be multiple Accept headers and we need to response with 406
            // if none match the method's @Produces
            List<String> accepts = requestContext.getAccept(false);
            if (!accepts.isEmpty()) {
                boolean hasAtLeastOneMatch = false;
                for (int i = 0; i < accepts.size(); i++) {
                    try {
                        boolean matches = acceptHeaderMatches(target, accepts.get(i));
                        if (matches) {
                            hasAtLeastOneMatch = true;
                            break;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // the provided header was not valid
                    }
                }
                if (!hasAtLeastOneMatch) {
                    throw new NotAcceptableException(INVALID_ACCEPT_HEADER_MESSAGE);
                }
            }

            requestContext.setProducesChecked(true);
        }

        requestContext.restart(target.value);
        requestContext.setRemaining(target.remaining);
        for (int i = 0; i < target.pathParamValues.length; ++i) {
            String pathParamValue = target.pathParamValues[i];
            if (pathParamValue == null) {
                break;
            }
            requestContext.setPathParamValue(i + parameterOffset, pathParamValue);
        }
    }

    /**
     * @return {@code true} if the provided string matches one of the {@code @Produces} values of the resource method
     * @throws IllegalArgumentException if the provided string cannot be parsed into a {@link MediaType}
     */
    private boolean acceptHeaderMatches(RequestMapper.RequestMatch<RuntimeResource> target, String accepts) {
        if ((accepts != null) && !accepts.equals(MediaType.WILDCARD)) {
            int commaIndex = accepts.indexOf(',');
            boolean multipleAcceptsValues = commaIndex >= 0;
            MediaType[] producesMediaTypes = target.value.getProduces().getSortedOriginalMediaTypes();
            if (!multipleAcceptsValues && (producesMediaTypes.length == 1)) {
                // the point of this branch is to eliminate any list creation or string indexing as none is needed
                MediaType providedMediaType = producesMediaTypes[0];
                return providedMediaType.isCompatible(toMediaType(accepts.trim()));
            } else if (multipleAcceptsValues && (producesMediaTypes.length == 1)) {
                // this is fairly common case, so we want it to be as fast as possible
                // we do that by manually splitting the accepts header and immediately checking
                // if the value is compatible with the produces media type
                boolean compatible = false;
                int begin = 0;

                do {
                    String acceptPart;
                    if (commaIndex == -1) { // this is the case where we are checking the remainder of the string
                        acceptPart = accepts.substring(begin);
                    } else {
                        acceptPart = accepts.substring(begin, commaIndex);
                    }
                    if (producesMediaTypes[0].isCompatible(toMediaType(acceptPart.trim()))) {
                        compatible = true;
                        break;
                    } else if (commaIndex == -1) { // we have reached the end and not found any compatible media types
                        break;
                    }
                    begin = commaIndex + 1; // the next part will start at the character after the comma
                    if (begin >= (accepts.length() - 1)) { // if we have reached this point, then are no compatible media types
                        break;
                    }
                    commaIndex = accepts.indexOf(',', begin);
                } while (true);

                return compatible;
            } else {
                // don't use any of the JAX-RS stuff from the various MediaType helper as we want to be as performant as possible
                List<MediaType> acceptsMediaTypes;
                if (accepts.contains(",")) {
                    String[] parts = accepts.split(",");
                    acceptsMediaTypes = new ArrayList<>(parts.length);
                    for (int i = 0; i < parts.length; i++) {
                        String part = parts[i];
                        acceptsMediaTypes.add(toMediaType(part.trim()));
                    }
                } else {
                    acceptsMediaTypes = Collections.singletonList(toMediaType(accepts));
                }
                return MediaTypeHelper.getFirstMatch(Arrays.asList(producesMediaTypes),
                        acceptsMediaTypes) != null;
            }
        }

        return true;
    }

    private MediaType toMediaType(String mediaTypeStr) {
        return MediaTypeHeaderDelegate.parse(mediaTypeStr);
    }

    private void throwNotFound(ResteasyReactiveRequestContext requestContext) {
        if (resumeOn404) {
            if (requestContext.resumeExternalProcessing()) {
                return;
            }
        }
        // the exception mapper needs access to request scoped beans, so make sure we have the context
        requestContext.requireCDIRequestScope();
        throw new NotFoundException("Unable to find matching target resource method");

    }

    private String getRemaining(ResteasyReactiveRequestContext requestContext) {
        return requestContext.getRemaining().isEmpty() ? "/" : requestContext.getRemaining();
    }

    public RoutingMappers getMappers() {
        return mappers;
    }

    public static final class RoutingMappers {
        private static final String ALL_HTTP_METHODS = "";
        private final Map<String, RequestMapper<RuntimeResource>> mappers;
        private Map<String, RequestMapper<RuntimeResource>> immutableMappers;

        private RoutingMappers(Map<String, RequestMapper<RuntimeResource>> mappers,
                Map<String, RequestMapper<RuntimeResource>> immutableMappers) {
            this.mappers = mappers;
            this.immutableMappers = immutableMappers;
        }

        public static Builder builder() {
            return new Builder();
        }

        private Set<String> mutableHttpMethods() {
            return mappers.keySet();
        }

        public static String allHttpMethods() {
            return ALL_HTTP_METHODS;
        }

        public static boolean isAllHttpMethods(String mappersKey) {
            return ALL_HTTP_METHODS.equals(mappersKey);
        }

        public RequestMapper<RuntimeResource> get(String httpMethod) {
            if (httpMethod == null) {
                throw new NullPointerException();
            }
            return mappers.get(httpMethod);
        }

        private Set<Map.Entry<String, RequestMapper<RuntimeResource>>> mutableEntrySet() {
            return mappers.entrySet();
        }

        public Set<Map.Entry<String, RequestMapper<RuntimeResource>>> entrySet() {
            var immutableMappers = this.immutableMappers;
            if (immutableMappers == null) {
                immutableMappers = Collections.unmodifiableMap(mappers);
                this.immutableMappers = immutableMappers;
            }
            return immutableMappers.entrySet();
        }

        private Collection<RequestMapper<RuntimeResource>> mutableRequestMappers() {
            return this.mappers.values();
        }

        public void forEach(BiConsumer<String, RequestMapper<RuntimeResource>> consumer) {
            mappers.forEach(consumer);
        }

        public static class Builder {
            private Map<String, RequestMapper<RuntimeResource>> mappers = new HashMap<>();

            /**
             * In order to specify a mapper for all HTTP methods, use {@code null} as the {@code httpMethod}.<br>
             * The resulting {@link RoutingMappers} will map it to {@link RoutingMappers#allHttpMethods()}.
             */
            public Builder addMapper(String httpMethod, RequestMapper<RuntimeResource> mapper) {
                var mappers = this.mappers;
                if (mappers == null) {
                    // this can happen if the Builder is reused
                    mappers = new HashMap<>();
                    this.mappers = mappers;
                }
                mappers.put(asMappingKey(httpMethod), mapper);
                return this;
            }

            public RoutingMappers build() {
                var mappers = this.mappers;
                this.mappers = null;
                return fromMap(mappers);
            }

            private static RoutingMappers fromMap(Map<String, RequestMapper<RuntimeResource>> mappers) {
                int mappings = mappers.size();
                final Map<String, RequestMapper<RuntimeResource>> unsafeMappers;
                final Map<String, RequestMapper<RuntimeResource>> immutableMappers;
                if (mappings == 0) {
                    unsafeMappers = Map.of();
                    immutableMappers = unsafeMappers;
                } else if (mappings == 1) {
                    unsafeMappers = Map.copyOf(mappers);
                    immutableMappers = unsafeMappers;
                } else {
                    unsafeMappers = mappers;
                    // let the mappers lazily populate it on demand
                    immutableMappers = null;
                }
                return new RoutingMappers(unsafeMappers, immutableMappers);
            }
        }

        private static String asMappingKey(String httpMethod) {
            if (httpMethod == null) {
                return ALL_HTTP_METHODS;
            }
            if (isAllHttpMethods(httpMethod)) {
                throw new IllegalArgumentException(
                        "httpMethod cannot be an empty string which is a reserved value for all HTTP methods");
            }
            return httpMethod;
        }

        public static RoutingMappers join(Iterable<RoutingMappers> mappers) {
            Map<String, ArrayList<RequestMapper.RequestPath<RuntimeResource>>> newMapper = new HashMap<>();
            for (RoutingMappers mapper : mappers) {
                for (Map.Entry<String, RequestMapper<RuntimeResource>> entry : mapper.mutableEntrySet()) {
                    assert entry.getKey() != null;
                    ArrayList<RequestMapper.RequestPath<RuntimeResource>> list = newMapper.get(entry.getKey());
                    if (list == null) {
                        newMapper.put(entry.getKey(), list = new ArrayList<>());
                    }
                    list.addAll(entry.getValue().getTemplates());
                }
            }
            Map<String, RequestMapper<RuntimeResource>> finalResult = new HashMap<>();
            for (Map.Entry<String, ArrayList<RequestMapper.RequestPath<RuntimeResource>>> i : newMapper.entrySet()) {
                assert i.getKey() != null;
                finalResult.put(i.getKey(), new RequestMapper<>(i.getValue()));
            }
            return Builder.fromMap(finalResult);
        }

    }
}
