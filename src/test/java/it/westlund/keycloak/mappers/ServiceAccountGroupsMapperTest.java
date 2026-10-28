package it.westlund.keycloak.mappers;

import static it.westlund.keycloak.mappers.LinkedUserGroupResolverTest.ATTR;
import static it.westlund.keycloak.mappers.LinkedUserGroupResolverTest.CLIENT_ID;
import static it.westlund.keycloak.mappers.LinkedUserGroupResolverTest.LDAP_ID;
import static it.westlund.keycloak.mappers.LinkedUserGroupResolverTest.group;
import static it.westlund.keycloak.mappers.LinkedUserGroupResolverTest.user;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.OAuthErrorException;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.ClientModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.OIDCAttributeMapperHelper;
import org.keycloak.representations.AccessToken;
import org.keycloak.services.ErrorResponseException;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.UserStorageProviderModel;
import org.keycloak.storage.user.UserQueryMethodsProvider;

/**
 * Exercises the mapper end-to-end over mocked Keycloak models: service-account gating,
 * LDAP provider selection, fail closed on/off, claim output and caching.
 */
class ServiceAccountGroupsMapperTest {

    private static final String REALM_ID = "realm-id";
    private static final String CLIENT_UUID = "client-uuid";

    private final AtomicLong now = new AtomicLong(1_000);
    private final ServiceAccountGroupsMapper mapper = new ServiceAccountGroupsMapper();

    private final KeycloakSession session = mock(KeycloakSession.class);
    private final RealmModel realm = mock(RealmModel.class);
    private final UserSessionModel userSession = mock(UserSessionModel.class);
    private final UserModel serviceAccount = mock(UserModel.class);
    private final UserStorageProvider ldapProvider =
            mock(UserStorageProvider.class, withSettings().extraInterfaces(UserQueryMethodsProvider.class));
    private final UserQueryMethodsProvider ldapSearch = (UserQueryMethodsProvider) ldapProvider;

    private final Map<String, String> config = new HashMap<>();

    @BeforeEach
    void setUp() {
        mapper.setCache(new TtlCache<>(100, now::get));

        when(userSession.getUser()).thenReturn(serviceAccount);
        when(userSession.getRealm()).thenReturn(realm);
        when(serviceAccount.getServiceAccountClientLink()).thenReturn(CLIENT_UUID);

        ClientModel client = mock(ClientModel.class);
        when(client.getClientId()).thenReturn(CLIENT_ID);
        when(realm.getId()).thenReturn(REALM_ID);
        when(realm.getClientById(CLIENT_UUID)).thenReturn(client);
        realmHasComponents(ldapComponent(LDAP_ID, "corp-ldap", true, 0));
        when(session.getProvider(eq(UserStorageProvider.class), any(ComponentModel.class))).thenReturn(ldapProvider);

        config.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "groups");
        config.put(OIDCAttributeMapperHelper.INCLUDE_IN_ACCESS_TOKEN, "true");
        config.put(ServiceAccountGroupsMapper.USER_ATTRIBUTE, ATTR);
        config.put(ServiceAccountGroupsMapper.CACHE_TTL, "60");
    }

    private static ComponentModel ldapComponent(String id, String name, boolean enabled, int priority) {
        UserStorageProviderModel model = new UserStorageProviderModel();
        model.setId(id);
        model.setName(name);
        model.setProviderId("ldap");
        model.setProviderType(UserStorageProvider.class.getName());
        model.setEnabled(enabled);
        model.setPriority(priority);
        return model;
    }

    private void realmHasComponents(ComponentModel... components) {
        when(realm.getComponentsStream(REALM_ID, UserStorageProvider.class.getName()))
                .thenAnswer(inv -> Stream.of(components));
    }

    private void ldapReturns(UserModel... users) {
        when(ldapSearch.searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID)).thenAnswer(inv -> Stream.of(users));
    }

    private AccessToken map() {
        ProtocolMapperModel model = new ProtocolMapperModel();
        model.setName("sa-groups");
        model.setProtocolMapper(ServiceAccountGroupsMapper.PROVIDER_ID);
        model.setConfig(config);
        AccessToken token = new AccessToken();
        mapper.setClaim(token, model, userSession, session, null);
        return token;
    }

    @Test
    void addsGroupsOfLinkedLdapUserAsMultivaluedClaim() {
        ldapReturns(user(LDAP_ID, group("readers", null), group("writers", null)));

        assertEquals(List.of("readers", "writers"), map().getOtherClaims().get("groups"));
    }

    @Test
    void ignoresRegularUsers() {
        when(serviceAccount.getServiceAccountClientLink()).thenReturn(null);

        assertFalse(map().getOtherClaims().containsKey("groups"));
        verifyNoInteractions(ldapProvider);
    }

    @Test
    void localUserWithAttributeGetsNothing() {
        ldapReturns(user(null, group("admins", null)));

        assertEquals(List.of(), map().getOtherClaims().get("groups"));
    }

    @Test
    void noMatchIssuesTokenWithEmptyClaim() {
        ldapReturns();

        assertEquals(List.of(), map().getOtherClaims().get("groups"));
    }

    @Test
    void noMatchWithNestedClaimNameCreatesNestedEmptyList() {
        config.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "authz.groups");
        ldapReturns();

        assertEquals(Map.of("groups", List.of()), map().getOtherClaims().get("authz"));
    }

    @Test
    void nestedClaimNameIsHonouredForGroups() {
        config.put(OIDCAttributeMapperHelper.TOKEN_CLAIM_NAME, "authz.groups");
        ldapReturns(user(LDAP_ID, group("readers", null)));

        assertEquals(Map.of("groups", List.of("readers")), map().getOtherClaims().get("authz"));
    }

    @Test
    void ldapFailureFailsClosedWithServerError() {
        when(ldapSearch.searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID))
                .thenThrow(new IllegalStateException("LDAP unreachable"));

        ErrorResponseException e = assertThrows(ErrorResponseException.class, this::map);
        assertEquals(OAuthErrorException.SERVER_ERROR, e.getError());
        assertEquals(500, e.getResponse().getStatus());
    }

    @Test
    void ambiguousLinkFailsClosed() {
        ldapReturns(user(LDAP_ID), user(LDAP_ID));

        assertThrows(ErrorResponseException.class, this::map);
    }

    @Test
    void failOpenIssuesTokenWithoutClaim() {
        config.put(ServiceAccountGroupsMapper.FAIL_CLOSED, "false");
        ldapReturns(user(LDAP_ID), user(LDAP_ID));

        assertFalse(map().getOtherClaims().containsKey("groups"));
    }

    @Test
    void missingLdapProviderFailsClosed() {
        realmHasComponents();

        assertThrows(ErrorResponseException.class, this::map);
    }

    @Test
    void cachesSuccessfulLookupUntilTtlExpires() {
        ldapReturns(user(LDAP_ID, group("readers", null)));

        map();
        map();
        verify(ldapSearch, times(1)).searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID);

        now.addAndGet(60_000);
        map();
        verify(ldapSearch, times(2)).searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID);
    }

    @Test
    void zeroTtlDisablesCache() {
        config.put(ServiceAccountGroupsMapper.CACHE_TTL, "0");
        ldapReturns(user(LDAP_ID, group("readers", null)));

        map();
        map();
        verify(ldapSearch, times(2)).searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID);
    }

    @Test
    void failuresAreNotCached() {
        when(ldapSearch.searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID))
                .thenThrow(new IllegalStateException("LDAP unreachable"))
                .thenAnswer(inv -> Stream.of(user(LDAP_ID, group("readers", null))));

        assertThrows(ErrorResponseException.class, this::map);
        assertEquals(List.of("readers"), map().getOtherClaims().get("groups"));
    }

    @Test
    void fullPathAndNameResultsAreCachedSeparately() {
        ldapReturns(user(LDAP_ID, group("readers", group("apps", null))));

        assertEquals(List.of("readers"), map().getOtherClaims().get("groups"));
        config.put(ServiceAccountGroupsMapper.FULL_PATH, "true");
        assertEquals(List.of("/apps/readers"), map().getOtherClaims().get("groups"));
    }

    @Test
    void blankProviderNameSelectsFirstEnabledLdapByPriority() {
        realmHasComponents(
                ldapComponent("disabled", "a", false, 0),
                ldapComponent("second", "b", true, 20),
                ldapComponent("first", "c", true, 10));

        assertEquals("first", ServiceAccountGroupsMapper.findLdapProvider(realm, "").getId());
    }

    @Test
    void namedProviderIsSelectedByName() {
        realmHasComponents(ldapComponent("one", "a", true, 0), ldapComponent("two", "b", true, 10));

        assertEquals("two", ServiceAccountGroupsMapper.findLdapProvider(realm, "b").getId());
    }

    @Test
    void namedDisabledProviderIsAnError() {
        realmHasComponents(ldapComponent("one", "a", false, 0));

        assertThrows(LinkedUserGroupResolver.LookupException.class,
                () -> ServiceAccountGroupsMapper.findLdapProvider(realm, "a"));
    }

    @Test
    void nonLdapStorageProvidersAreIgnored() {
        ComponentModel other = ldapComponent("kerberos", "krb", true, 0);
        other.setProviderId("kerberos");
        realmHasComponents(other);

        assertThrows(LinkedUserGroupResolver.LookupException.class,
                () -> ServiceAccountGroupsMapper.findLdapProvider(realm, ""));
    }

    @Test
    void configDefaultsAreSafe() {
        ServiceAccountGroupsMapper.MapperConfig defaults = ServiceAccountGroupsMapper.MapperConfig.from(new ProtocolMapperModel());

        assertEquals("oauthClientID", defaults.userAttribute());
        assertEquals(60, defaults.cacheTtlSeconds());
        assertEquals(true, defaults.failClosed());
        assertEquals(false, defaults.fullPath());
    }
}
