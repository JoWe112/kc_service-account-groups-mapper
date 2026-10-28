package it.westlund.keycloak.mappers;

import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.ModelToRepresentation;
import org.keycloak.storage.user.UserQueryMethodsProvider;

/**
 * Resolves the groups of the federated user that is linked to a client through a user
 * attribute (e.g. {@code oauthClientID=<clientId>}).
 *
 * <p>Kept free of session/realm lookups so the rules can be unit-tested with mocked models:
 * <ul>
 *   <li>only users whose {@link UserModel#getFederationLink()} equals the configured LDAP
 *       component id are accepted (local users carrying the same attribute are ignored);</li>
 *   <li>no accepted user &rarr; empty list;</li>
 *   <li>more than one accepted user &rarr; {@link LookupException} (ambiguous link);</li>
 *   <li>any failure from the search or group read propagates to the caller, which decides
 *       between fail closed and fail open.</li>
 * </ul>
 */
final class LinkedUserGroupResolver {

    /** Thrown when the lookup cannot produce a trustworthy answer. */
    static final class LookupException extends RuntimeException {
        LookupException(String message) {
            super(message);
        }
    }

    private LinkedUserGroupResolver() {
    }

    /**
     * @param search          provider to query; in production the configured LDAP provider
     * @param realm           realm of the client
     * @param ldapComponentId component id the linked user's federation link must point to
     * @param attributeName   Keycloak user attribute holding the client id
     * @param clientId        the client's {@code clientId}
     * @param fullPath        {@code true} for full group paths, {@code false} for group names
     * @return group names/paths of the linked user, or an empty list if there is none
     * @throws LookupException if more than one federated user is linked to the client
     */
    static List<String> resolve(UserQueryMethodsProvider search, RealmModel realm, String ldapComponentId,
                                String attributeName, String clientId, boolean fullPath) {
        List<UserModel> linked;
        // Collect inside try-with-resources: LDAP import-on-demand runs lazily while the stream is consumed.
        try (Stream<UserModel> users = search.searchForUserByUserAttributeStream(realm, attributeName, clientId)) {
            linked = users
                    .filter(user -> ldapComponentId.equals(user.getFederationLink()))
                    .limit(2) // two is enough to detect ambiguity
                    .toList();
        }

        if (linked.isEmpty()) {
            return List.of();
        }
        if (linked.size() > 1) {
            throw new LookupException("More than one federated user has " + attributeName + "=" + clientId);
        }

        Function<GroupModel, String> toClaimValue = fullPath ? ModelToRepresentation::buildGroupPath : GroupModel::getName;
        try (Stream<GroupModel> groups = linked.get(0).getGroupsStream()) {
            return groups.map(toClaimValue).sorted().toList();
        }
    }
}
