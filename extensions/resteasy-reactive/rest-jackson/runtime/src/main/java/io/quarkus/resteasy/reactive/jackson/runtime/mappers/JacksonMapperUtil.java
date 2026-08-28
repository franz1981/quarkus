package io.quarkus.resteasy.reactive.jackson.runtime.mappers;

import java.lang.reflect.Type;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectWriter;

import io.quarkus.arc.Arc;
import io.quarkus.arc.ArcContainer;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.resteasy.reactive.jackson.runtime.security.RolesAllowedConfigExpStorage;
import io.quarkus.security.identity.SecurityIdentity;

public class JacksonMapperUtil {

    public static JavaType getGenericRootType(Type genericType, ObjectWriter defaultWriter) {
        // Jackson needs additional type information when serializing generic types, as discussed here:
        // https://github.com/FasterXML/jackson-databind/issues/336 and https://github.com/FasterXML/jackson-databind/issues/23
        // Parts of the code were taken from org.jboss.resteasy.plugins.providers.jackson.ResteasyJackson2Provider
        // which was used in quarkus-resteasy to handle this situation.
        JavaType rootType = null;
        if (genericType != null) {
            /*
             * 10-Jan-2011, tatu: as per [JACKSON-456], it's not safe to just force root
             * type since it prevents polymorphic type serialization. Since we really
             * just need this for generics, let's only use generic type if it's truly
             * generic.
             */
            if (genericType.getClass() != Class.class) {
                rootType = defaultWriter.getTypeFactory().constructType(genericType);
                /*
                 * 26-Feb-2011, tatu: To help with [JACKSON-518], we better recognize cases where
                 * type degenerates back into "Object.class" (as is the case with plain TypeVariable,
                 * for example), and not use that.
                 */
                if (rootType.getRawClass() == Object.class) {
                    rootType = null;
                }
            }
        }

        return rootType;
    }

    public static boolean includeSecureField(String[] rolesAllowed) {
        SecurityIdentity securityIdentity = RolesAllowedHolder.SECURITY_IDENTITY;
        if (securityIdentity == null) {
            return false;
        }

        RolesAllowedConfigExpStorage rolesConfigExpStorage = RolesAllowedHolder.ROLES_ALLOWED_CONFIG_EXP_STORAGE;
        for (String role : rolesAllowed) {
            if (rolesConfigExpStorage != null) {
                // role config expression => resolved roles
                String[] roles = rolesConfigExpStorage.getRoles(role);
                if (roles != null) {
                    for (String r : roles) {
                        if (securityIdentity.hasRole(r)) {
                            return true;
                        }
                    }
                    continue;
                }
                // at this point, we know 'role' is not a configuration expression
            }
            if (securityIdentity.hasRole(role)) {
                return true;
            }
        }
        return false;
    }

    private static class RolesAllowedHolder {

        private static final ArcContainer ARC_CONTAINER = Arc.container();

        private static final SecurityIdentity SECURITY_IDENTITY = createSecurityIdentity();

        private static final RolesAllowedConfigExpStorage ROLES_ALLOWED_CONFIG_EXP_STORAGE = createRolesAllowedConfigExpStorage();

        private static SecurityIdentity createSecurityIdentity() {
            if (ARC_CONTAINER == null) {
                return null;
            }
            InstanceHandle<SecurityIdentity> instance = ARC_CONTAINER.instance(SecurityIdentity.class);
            return instance.isAvailable() ? instance.get() : null;
        }

        private static RolesAllowedConfigExpStorage createRolesAllowedConfigExpStorage() {
            if (ARC_CONTAINER == null) {
                return null;
            }
            InstanceHandle<RolesAllowedConfigExpStorage> rolesAllowedConfigExpStorage = ARC_CONTAINER
                    .instance(RolesAllowedConfigExpStorage.class);
            return rolesAllowedConfigExpStorage.isAvailable() ? rolesAllowedConfigExpStorage.get() : null;
        }
    }
}
