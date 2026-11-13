package io.quarkus.resteasy.reactive.qute.runtime;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;

import io.quarkus.qute.Engine;
import io.quarkus.qute.Template;
import io.quarkus.qute.TemplateException;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.qute.Variant;
import io.smallrye.mutiny.Uni;

final class Util {

    private Util() {
    }

    static Uni<String> toUni(TemplateInstance instance, Engine engine) {
        return withTimeout(instance.createUni(), instance, engine);
    }

    static Uni<Utf8Output> toUtf8Uni(TemplateInstance instance, Engine engine) {
        return withTimeout(Uni.createFrom().completionStage(() -> Utf8Output.render(instance)), instance, engine);
    }

    private static <T> Uni<T> withTimeout(Uni<T> uni, TemplateInstance instance, Engine engine) {
        if (!engine.useAsyncTimeout()) {
            // Make sure the timeout is always used
            long timeout = instance.getTimeout();
            uni = uni.ifNoItem().after(Duration.ofMillis(timeout))
                    .failWith(() -> new TemplateException(instance + " rendering timeout [" + timeout + "ms] occurred"));
        }
        return uni;
    }

    /**
     * Writer interceptors keep getting a String. Without a selected media type, the String keeps the default
     * {@code text/plain} of RESTEasy Reactive.
     */
    static boolean useUtf8Output(MediaType mediaType, boolean hasWriterInterceptors) {
        return mediaType != null && !hasWriterInterceptors;
    }

    @SuppressWarnings("unchecked")
    static MediaType setSelectedVariant(TemplateInstance result,
            Request request, List<Locale> acceptableLanguages) {
        List<Variant> quteVariants = List.of();
        Object variantsAttr = result.getAttribute(TemplateInstance.VARIANTS);
        if (variantsAttr != null) {
            quteVariants = (List<Variant>) variantsAttr;
        } else {
            // If no variants are available then try to use the template variant
            Template template = result.getTemplate();
            if (template.getVariant().isPresent()) {
                quteVariants = List.of(template.getVariant().get());
            }
        }
        if (!quteVariants.isEmpty()) {
            List<jakarta.ws.rs.core.Variant> jaxRsVariants = new ArrayList<>(quteVariants.size());
            for (Variant variant : quteVariants) {
                jaxRsVariants.add(new jakarta.ws.rs.core.Variant(MediaType.valueOf(variant.getMediaType()), variant.getLocale(),
                        variant.getEncoding()));
            }
            jakarta.ws.rs.core.Variant selected = request
                    .selectVariant(jaxRsVariants);

            if (selected != null) {
                Locale selectedLocale = selected.getLanguage();
                if (selectedLocale == null) {
                    if (!acceptableLanguages.isEmpty()) {
                        selectedLocale = acceptableLanguages.get(0);
                    }
                }
                result.setAttribute(TemplateInstance.SELECTED_VARIANT,
                        new Variant(selectedLocale, selected.getMediaType().toString(),
                                selected.getEncoding()));
                return selected.getMediaType();
            }
        }
        return null;
    }
}
