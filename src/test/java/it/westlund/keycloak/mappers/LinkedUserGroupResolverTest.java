package it.westlund.keycloak.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.user.UserQueryMethodsProvider;

class LinkedUserGroupResolverTest {

    static final String LDAP_ID = "ldap-component-id";
    static final String ATTR = "oauthClientID";
    static final String CLIENT_ID = "billing-service";

    private final RealmModel realm = mock(RealmModel.class);
    private final UserQueryMethodsProvider search = mock(UserQueryMethodsProvider.class);

    static UserModel user(String federationLink, GroupModel... groups) {
        UserModel user = mock(UserModel.class);
        when(user.getFederationLink()).thenReturn(federationLink);
        when(user.getGroupsStream()).thenAnswer(inv -> Stream.of(groups));
        return user;
    }

    static GroupModel group(String name, GroupModel parent) {
        GroupModel group = mock(GroupModel.class);
        when(group.getName()).thenReturn(name);
        when(group.getParent()).thenReturn(parent);
        return group;
    }

    private void searchReturns(UserModel... users) {
        when(search.searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID)).thenAnswer(inv -> Stream.of(users));
    }

    private List<String> resolve(boolean fullPath) {
        return LinkedUserGroupResolver.resolve(search, realm, LDAP_ID, ATTR, CLIENT_ID, fullPath);
    }

    @Test
    void returnsGroupNamesOfLinkedLdapUser() {
        searchReturns(user(LDAP_ID, group("writers", null), group("readers", null)));

        assertEquals(List.of("readers", "writers"), resolve(false));
    }

    @Test
    void returnsFullPathsWhenConfigured() {
        GroupModel parent = group("apps", null);
        searchReturns(user(LDAP_ID, group("readers", parent)));

        assertEquals(List.of("/apps/readers"), resolve(true));
    }

    @Test
    void noMatchReturnsEmptyList() {
        searchReturns();

        assertTrue(resolve(false).isEmpty());
    }

    @Test
    void localUserWithSameAttributeIsIgnored() {
        UserModel local = user(null, group("admins", null));
        searchReturns(local);

        assertTrue(resolve(false).isEmpty());
        verify(local, never()).getGroupsStream();
    }

    @Test
    void userFromOtherFederationProviderIsIgnored() {
        searchReturns(user("other-ldap", group("admins", null)), user(LDAP_ID, group("readers", null)));

        assertEquals(List.of("readers"), resolve(false));
    }

    @Test
    void multipleLinkedLdapUsersIsAnError() {
        searchReturns(user(LDAP_ID, group("a", null)), user(LDAP_ID, group("b", null)));

        assertThrows(LinkedUserGroupResolver.LookupException.class, () -> resolve(false));
    }

    @Test
    void localDuplicateDoesNotMakeLookupAmbiguous() {
        searchReturns(user(null), user(LDAP_ID, group("readers", null)));

        assertEquals(List.of("readers"), resolve(false));
    }

    @Test
    void searchFailurePropagates() {
        when(search.searchForUserByUserAttributeStream(realm, ATTR, CLIENT_ID))
                .thenThrow(new IllegalStateException("LDAP unreachable"));

        assertThrows(IllegalStateException.class, () -> resolve(false));
    }

    @Test
    void groupReadFailurePropagates() {
        UserModel linked = mock(UserModel.class);
        when(linked.getFederationLink()).thenReturn(LDAP_ID);
        when(linked.getGroupsStream()).thenThrow(new IllegalStateException("LDAP unreachable"));
        searchReturns(linked);

        assertThrows(IllegalStateException.class, () -> resolve(false));
    }
}
