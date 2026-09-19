package dev.manestack.api;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

@Singleton
public class CurrentUser {

    @Inject
    CurrentIdentityAssociation identity;

    public Uni<Integer> getUserId() {
        return identity.getDeferredIdentity()
            .map(id -> Integer.parseInt(id.getPrincipal().getName()));
    }

    public Uni<String> getUsername() {
        return identity.getDeferredIdentity()
            .map(id -> id.getPrincipal().getName());
    }

    public Uni<Integer> getUserIdOrNull() {
        return identity.getDeferredIdentity()
            .map(id -> {
                try {
                    return Integer.parseInt(id.getPrincipal().getName());
                } catch (Exception e) {
                    return null;
                }
            });
    }
}
