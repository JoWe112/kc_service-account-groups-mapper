package it.westlund.keycloak.mappers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.ws.rs.core.Response;

import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.OAuthErrorException;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.LDAPConstants;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.ProtocolMapperUtils;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.UserQueryMethodsProvider;
import org.keycloak.utils.JsonUtils;

/**
 * OIDC protocol mapper that adds the groups of a linked federated (LDAP) user to the access
 * token of a client's service account (Client Credentials flow).
 *
 * <p>A client is linked to an LDAP user through a user attribute (default
 * {@code oauthClientID}) whose value is the client's {@code clientId}. The attribute is
 * populated by a {@code user-attribute-ldap-mapper} on the LDAP provider, and the groups by
 * a {@code group-ldap-mapper}. Only users whose federation link points to the configured
 * LDAP provider are accepted, so a local user carrying the same attribute cannot inherit a
 * client's permissions.
 *
 * <p>The search is sent to the configured LDAP provider through the generic
 * {@link UserQueryMethodsProvider} SPI rather than {@code session.users()}: since Keycloak
 * 26.x the {@code UserStorageManager} catches provider failures ("graceful degradation") and
 * returns an empty result, which would make an LDAP outage indistinguishable from "no
 * linked user" and defeat fail closed.
 */
public class ServiceAccountGroupsMapper extends AbstractOIDCProtocolMapper implements OIDCAccessTokenMapper {

    public static final String PROVIDER_ID = "oidc-service-account-groups-mapper";

    static final String USER_ATTRIBUTE = "user.attribute";
    static final String LDAP_PROVIDER = "ldap.provider";
    static final String CACHE_TTL = "cache.ttl";
    static final String FULL_PATH = "full.path";
    static final String FAIL_CLOSED = "fail.closed";

    static final String DEFAULT_CLAIM_NAME = "groups";
    static final String DEFAULT_USER_ATTRIBUTE = "oauthClientID";
    static final long DEFAULT_CACHE_TTL_SECONDS = 60;
    static final int DEFAULT_CACHE_MAX_ENTRIES = 10_000;

    /** SPI option: {@code --spi-protocol-mapper--oidc-service-account-groups-mapper--cache-max-entries}. */
    static final String CACHE_MAX_ENTRIES_OPTION = "cacheMaxEntries";

    private static final Logger LOG = Logger.getLogger(ServiceAccountGroupsMapper.class);

    private static final List<ProviderConfigProperty> CONFIG_PROPERTIES = new ArrayList<>();

    static {
        OIDCAttributeMapperHelper.addTokenClaimNameConfig(CONFIG_PROPERTIES);
        CONFIG_PROPERTIES.get(CONFIG_PROPERTIES.size() - 1).setDefaultValue(DEFAULT_CLAIM_NAME);

        addProperty(USER_ATTRIBUTE, "User attribute", ProviderConfigProperty.STRING_TYPE, DEFAULT_USER_ATTRIBUTE,
                "Keycloak user attribute (populated by a user-attribute-ldap-mapper) whose value is the client's clientId.");
        addProperty(LDAP_PROVIDER, "LDAP provider", ProviderConfigProperty.STRING_TYPE, "",
                "Name of the LDAP user federation provider the linked user must belong to. "
                        + "Empty = the first enabled LDAP provider in the realm (by priority).");
        addProperty(CACHE_TTL, "Cache TTL (seconds)", ProviderConfigProperty.INTEGER_TYPE, String.valueOf(DEFAULT_CACHE_TTL_SECONDS),
                "How long a successful lookup is cached per realm/attribute/client. 0 disables the cache.");
        addProperty(FULL_PATH, "Full group path", ProviderConfigProperty.BOOLEAN_TYPE, "false",
                "Emit full group paths (e.g. /parent/child) instead of group names.");
        addProperty(FAIL_CLOSED, "Fail closed", ProviderConfigProperty.BOOLEAN_TYPE, "true",
                "If the lookup fails (LDAP unreachable, ambiguous link, missing provider), reject the token request "
                        + "with server_error. If off, the token is issued without the claim.");

        OIDCAttributeMapperHelper.addIncludeInTokensConfig(CONFIG_PROPERTIES, ServiceAccountGroupsMapper.class);
    }

    private static void addProperty(String name, String label, String type, String defaultValue, String helpText) {
        ProviderConfigProperty property = new ProviderConfigProperty();
        property.setName(name);
        property.setLabel(label);
        property.setType(type);
        property.setDefaultValue(defaultValue);
        property.setHelpText(helpText);
        CONFIG_PROPERTIES.add(property);
    }

    /** Cache key; includes the LDAP component and path mode so mapper configurations never share answers. */
    record CacheKey(String realmId, String ldapComponentId, String attributeName, String clientId, boolean fullPath) {
    }

    /**
     * Shared by all mapper configurations: Keycloak creates one factory instance and
     * {@link #create} returns it. Replaced in {@link #init} once the SPI config is known.
     */
    private volatile TtlCache<CacheKey, List<String>> cache =
            new TtlCache<>(DEFAULT_CACHE_MAX_ENTRIES, System::currentTimeMillis);

    @Override
    public void init(Config.Scope config) {
        int maxEntries = config.getInt(CACHE_MAX_ENTRIES_OPTION, DEFAULT_CACHE_MAX_ENTRIES);
        cache = new TtlCache<>(maxEntries, System::currentTimeMillis);
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "Service account groups (via linked user)";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Adds the groups of the federated (LDAP) user whose attribute matches the client's clientId "
                + "to service account access tokens (Client Credentials flow).";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return CONFIG_PROPERTIES;
    }

    /**
     * Adds the claim for service accounts only: the group list, or {@code []} when no linked
     * user exists. On lookup failure either throws {@code server_error} (fail closed) or leaves
     * the token without the claim (fail open).
     */
    @Override
    protected void setClaim(IDToken token, ProtocolMapperModel mappingModel, UserSessionModel userSession,
                            KeycloakSession session, ClientSessionContext clientSessionCtx) {
        UserModel user = userSession.getUser();
        if (user == null || user.getServiceAccountClientLink() == null) {
            return;
        }

        MapperConfig config = MapperConfig.from(mappingModel);
        List<String> groups;
        try {
            groups = lookupGroups(session, userSession.getRealm(), user.getServiceAccountClientLink(), config);
        } catch (RuntimeException e) {
            if (config.failClosed()) {
                // Details are logged server-side only; the client gets a generic OAuth error.
                LOG.errorf(e, "Group lookup failed for service account of client %s (mapper '%s'); rejecting token request",
                        user.getServiceAccountClientLink(), mappingModel.getName());
                throw new ErrorResponseException(OAuthErrorException.SERVER_ERROR,
                        "Unable to resolve client groups", Response.Status.INTERNAL_SERVER_ERROR);
            }
            LOG.warnf(e, "Group lookup failed for service account of client %s (mapper '%s'); issuing token without groups",
                    user.getServiceAccountClientLink(), mappingModel.getName());
            return;
        }

        if (groups.isEmpty()) {
            // mapClaim drops empty collections; emit [] so "no groups" differs from "lookup failed" (no claim).
            putEmptyListClaim(token, mappingModel.getConfig().get(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME));
            return;
        }

        // Work on a copy so the shared mapper model is not mutated; force a JSON array claim.
        ProtocolMapperModel claimModel = new ProtocolMapperModel();
        claimModel.setName(mappingModel.getName());
        Map<String, String> claimConfig = new HashMap<>(mappingModel.getConfig());
        claimConfig.put(ProtocolMapperUtils.MULTIVALUED, "true");
        claimModel.setConfig(claimConfig);
        OIDCAttributeMapperHelper.mapClaim(token, claimModel, groups);
    }

    /**
     * Sets {@code claimName} to an empty JSON array, honouring Keycloak's dotted (nested)
     * claim name convention.
     */
    @SuppressWarnings("unchecked")
    static void putEmptyListClaim(IDToken token, String claimName) {
        List<String> path = claimName == null ? List.of() : JsonUtils.splitClaimPath(claimName);
        if (path.isEmpty()) {
            return;
        }
        Map<String, Object> node = token.getOtherClaims();
        for (String part : path.subList(0, path.size() - 1)) {
            Object child = node.get(part);
            if (!(child instanceof Map)) {
                child = new HashMap<String, Object>();
                node.put(part, child);
            }
            node = (Map<String, Object>) child;
        }
        node.put(path.get(path.size() - 1), new ArrayList<String>());
    }

    /**
     * Resolves the groups for the client owning the service account, using the cache when
     * enabled. Only successful lookups are cached; failures always propagate.
     */
    List<String> lookupGroups(KeycloakSession session, RealmModel realm, String clientUuid, MapperConfig config) {
        ClientModel client = realm.getClientById(clientUuid);
        if (client == null) {
            throw new LinkedUserGroupResolver.LookupException("Service account client not found: " + clientUuid);
        }
        UserStorageProviderModel ldap = findLdapProvider(realm, config.ldapProviderName());

        CacheKey key = new CacheKey(realm.getId(), ldap.getId(), config.userAttribute(), client.getClientId(), config.fullPath());
        long ttlMillis = config.cacheTtlSeconds() * 1000L;
        if (ttlMillis > 0) {
            List<String> cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        // Same instance the UserStorageManager would use (session-scoped, keyed by component id).
        UserStorageProvider provider = session.getProvider(UserStorageProvider.class, ldap);
        if (!(provider instanceof UserQueryMethodsProvider query)) {
            throw new LinkedUserGroupResolver.LookupException("LDAP provider '" + ldap.getName() + "' does not support user queries");
        }

        List<String> groups = LinkedUserGroupResolver.resolve(query, realm, ldap.getId(),
                config.userAttribute(), client.getClientId(), config.fullPath());
        cache.put(key, groups, ttlMillis);
        return groups;
    }

    /**
     * Finds the LDAP user storage component used for the federation-link check.
     *
     * @param name component name, or blank for the first enabled LDAP provider by priority
     * @throws LinkedUserGroupResolver.LookupException if none matches, the name is ambiguous,
     *                                                 or the named provider is disabled
     */
    static UserStorageProviderModel findLdapProvider(RealmModel realm, String name) {
        List<UserStorageProviderModel> ldapProviders = realm
                .getComponentsStream(realm.getId(), UserStorageProvider.class.getName())
                .filter(c -> LDAPConstants.LDAP_PROVIDER.equals(c.getProviderId()))
                .map(UserStorageProviderModel::new)
                .toList();

        if (name == null || name.isBlank()) {
            return ldapProviders.stream()
                    .filter(UserStorageProviderModel::isEnabled)
                    .min(Comparator.comparingInt(UserStorageProviderModel::getPriority))
                    .orElseThrow(() -> new LinkedUserGroupResolver.LookupException("No enabled LDAP provider in realm"));
        }

        List<UserStorageProviderModel> named = ldapProviders.stream().filter(c -> name.equals(c.getName())).toList();
        if (named.isEmpty()) {
            throw new LinkedUserGroupResolver.LookupException("LDAP provider not found: " + name);
        }
        if (named.size() > 1) {
            throw new LinkedUserGroupResolver.LookupException("More than one LDAP provider named: " + name);
        }
        if (!named.get(0).isEnabled()) {
            throw new LinkedUserGroupResolver.LookupException("LDAP provider is disabled: " + name);
        }
        return named.get(0);
    }

    /** Replaces the cache; for tests only. */
    void setCache(TtlCache<CacheKey, List<String>> cache) {
        this.cache = cache;
    }

    /** Typed view of the mapper's admin-UI configuration, with defaults applied. */
    record MapperConfig(String userAttribute, String ldapProviderName, long cacheTtlSeconds, boolean fullPath,
                        boolean failClosed) {

        static MapperConfig from(ProtocolMapperModel model) {
            Map<String, String> c = model.getConfig() == null ? Map.of() : model.getConfig();
            String attribute = c.getOrDefault(USER_ATTRIBUTE, "");
            return new MapperConfig(
                    attribute.isBlank() ? DEFAULT_USER_ATTRIBUTE : attribute.trim(),
                    c.getOrDefault(LDAP_PROVIDER, "").trim(),
                    parseTtl(c.get(CACHE_TTL)),
                    Boolean.parseBoolean(c.getOrDefault(FULL_PATH, "false")),
                    // Missing value = fail closed (safe default).
                    !"false".equalsIgnoreCase(c.getOrDefault(FAIL_CLOSED, "true")));
        }

        private static long parseTtl(String value) {
            if (value == null || value.isBlank()) {
                return DEFAULT_CACHE_TTL_SECONDS;
            }
            try {
                return Math.max(0, Long.parseLong(value.trim()));
            } catch (NumberFormatException e) {
                LOG.warnf("Invalid cache TTL '%s'; caching disabled", value);
                return 0;
            }
        }
    }
}
