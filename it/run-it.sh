#!/usr/bin/env bash
# Integration test: real Keycloak + OpenLDAP in Docker.
#
# Verifies, against the built JAR in target/:
#   1. the mapper deploys and adds LDAP groups to a Client Credentials access token;
#   2. an LDAP user that has NOT been imported/synced is found (imported on demand);
#   3. a local user carrying the same attribute is ignored (federationLink check);
#   4. an unlinked client gets an empty groups claim ([]);
#   5. two LDAP users with the same client id -> server_error (fail closed) or no claim (fail open);
#   6. LDAP down -> server_error (fail closed).
#
# Usage: mvn package && it/run-it.sh     (requires docker, curl, python3)
set -euo pipefail

KEYCLOAK_IMAGE="${KEYCLOAK_IMAGE:-quay.io/keycloak/keycloak:26.8.1}"
LDAP_IMAGE="${LDAP_IMAGE:-osixia/openldap:1.5.0}"
PORT="${PORT:-18080}"
NET=kcsagm-it
KC=kcsagm-it-keycloak
LDAP=kcsagm-it-ldap
REALM=it
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/target/kc_service-account-groups-mapper.jar"

[[ -f "$JAR" ]] || { echo "Build first: mvn package" >&2; exit 1; }

failures=0
pass() { echo "  PASS: $*"; }
fail() { echo "  FAIL: $*"; failures=$((failures + 1)); }

cleanup() {
  if [[ $failures -gt 0 || -n "${KEEP_LOGS:-}" ]]; then
    docker logs "$KC" 2>&1 | grep -iE "service account|group lookup|ServiceAccountGroups|ERROR" | tail -40 || true
  fi
  docker rm -f "$KC" "$LDAP" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

echo "== Starting OpenLDAP and Keycloak"
docker network create "$NET" >/dev/null
docker run -d --name "$LDAP" --network "$NET" \
  -e LDAP_DOMAIN=example.org -e LDAP_ADMIN_PASSWORD=admin \
  -v "$ROOT/it/seed.ldif:/container/service/slapd/assets/config/bootstrap/ldif/custom/50-seed.ldif:ro" \
  "$LDAP_IMAGE" --copy-service >/dev/null
docker run -d --name "$KC" --network "$NET" -p "127.0.0.1:$PORT:8080" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v "$JAR:/opt/keycloak/providers/kc_service-account-groups-mapper.jar:ro" \
  "$KEYCLOAK_IMAGE" start-dev >/dev/null

for _ in $(seq 1 90); do
  curl -sf "http://127.0.0.1:$PORT/realms/master" >/dev/null && break
  sleep 2
done
curl -sf "http://127.0.0.1:$PORT/realms/master" >/dev/null || { echo "Keycloak did not start" >&2; failures=1; exit 1; }

kcadm() { docker exec -i "$KC" /opt/keycloak/bin/kcadm.sh "$@"; }
kcadm config credentials --server http://localhost:8080 --realm master --user admin --password admin >/dev/null

echo "== Configuring realm"
kcadm create realms -s realm="$REALM" -s enabled=true >/dev/null
REALM_ID=$(kcadm get "realms/$REALM" --fields id --format csv --noquotes)

# Allow unmanaged attributes so the local "imposter" user can carry oauthClientID (user profile, KC 24+).
kcadm get users/profile -r "$REALM" \
  | python3 -c 'import json,sys; p=json.load(sys.stdin); p["unmanagedAttributePolicy"]="ENABLED"; print(json.dumps(p))' \
  | kcadm update users/profile -r "$REALM" -f - >/dev/null

LDAP_ID=$(kcadm create components -r "$REALM" -i -f - <<EOF
{"name":"corp-ldap","providerId":"ldap","providerType":"org.keycloak.storage.UserStorageProvider","parentId":"$REALM_ID",
 "config":{"enabled":["true"],"priority":["0"],"vendor":["other"],"connectionUrl":["ldap://$LDAP:389"],
  "authType":["simple"],"bindDn":["cn=admin,dc=example,dc=org"],"bindCredential":["admin"],
  "usersDn":["ou=people,dc=example,dc=org"],"searchScope":["1"],"usernameLDAPAttribute":["uid"],
  "rdnLDAPAttribute":["uid"],"uuidLDAPAttribute":["entryUUID"],"userObjectClasses":["inetOrgPerson"],
  "editMode":["READ_ONLY"],"importEnabled":["true"],"syncRegistrations":["false"],
  "fullSyncPeriod":["-1"],"changedSyncPeriod":["-1"],"trustEmail":["false"]}}
EOF
)
kcadm create components -r "$REALM" -f - >/dev/null <<EOF
{"name":"oauthClientID","providerId":"user-attribute-ldap-mapper","providerType":"org.keycloak.storage.ldap.mappers.LDAPStorageMapper","parentId":"$LDAP_ID",
 "config":{"user.model.attribute":["oauthClientID"],"ldap.attribute":["employeeType"],"read.only":["true"],
  "always.read.value.from.ldap":["false"],"is.mandatory.in.ldap":["false"],"is.binary.attribute":["false"]}}
EOF
kcadm create components -r "$REALM" -f - >/dev/null <<EOF
{"name":"groups","providerId":"group-ldap-mapper","providerType":"org.keycloak.storage.ldap.mappers.LDAPStorageMapper","parentId":"$LDAP_ID",
 "config":{"groups.dn":["ou=groups,dc=example,dc=org"],"group.name.ldap.attribute":["cn"],"group.object.classes":["groupOfNames"],
  "preserve.group.inheritance":["false"],"membership.ldap.attribute":["member"],"membership.attribute.type":["DN"],
  "membership.user.ldap.attribute":["uid"],"mode":["READ_ONLY"],"user.roles.retrieve.strategy":["LOAD_GROUPS_BY_MEMBER_ATTRIBUTE"],
  "memberof.ldap.attribute":["memberOf"],"drop.non.existing.groups.during.sync":["false"],"ignore.missing.groups":["false"]}}
EOF

# Creates a confidential client with a service account and this mapper on its dedicated scope.
# cache.ttl=0 so every token request reaches LDAP; with a TTL, a cached result would still be
# served while LDAP is down and check 6 could not observe fail closed.
create_client() { # clientId failClosed(true|false)
  local id
  id=$(kcadm create clients -r "$REALM" -i -s clientId="$1" -s publicClient=false -s secret=secret \
        -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false)
  kcadm create "clients/$id/protocol-mappers/models" -r "$REALM" -f - >/dev/null <<EOF
{"name":"sa-groups","protocol":"openid-connect","protocolMapper":"oidc-service-account-groups-mapper",
 "config":{"claim.name":"groups","access.token.claim":"true","user.attribute":"oauthClientID","ldap.provider":"",
  "cache.ttl":"0","full.path":"false","fail.closed":"$2"}}
EOF
}
create_client billing-service true
create_client imposter-service true
create_client unlinked-service true
create_client dup-service true
create_client dup-open-service false

# Local (non-federated) user claiming to be imposter-service, member of a local group.
kcadm create groups -r "$REALM" -s name=admins >/dev/null
IMPOSTER_ID=$(kcadm create users -r "$REALM" -i -s username=imposter -s enabled=true \
               -s 'attributes={"oauthClientID":["imposter-service"]}')
ADMINS_ID=$(kcadm get groups -r "$REALM" -q search=admins --fields id --format csv --noquotes | head -1)
kcadm update "users/$IMPOSTER_ID/groups/$ADMINS_ID" -r "$REALM" -n >/dev/null

# token <clientId> -> prints "<http status> <json body>"
token() {
  curl -s -o /tmp/kcsagm-body.$$ -w '%{http_code}' -u "$1:secret" -d grant_type=client_credentials \
    "http://127.0.0.1:$PORT/realms/$REALM/protocol/openid-connect/token"
  echo " $(cat /tmp/kcsagm-body.$$)"; rm -f /tmp/kcsagm-body.$$
}
# claim <token response json> -> prints the groups claim of the access token as JSON (or "absent")
claim() {
  python3 -c '
import base64, json, sys
t = json.loads(sys.argv[1])["access_token"].split(".")[1]
p = json.loads(base64.urlsafe_b64decode(t + "=" * (-len(t) % 4)))
print(json.dumps(p["groups"]) if "groups" in p else "absent")' "$1"
}

echo "== 1+2. Linked LDAP user (never synced) -> groups claim"
# Timestamps from the Keycloak container clock (host and Docker VM clocks may differ).
T0=$(docker exec "$KC" date +%s%3N)
read -r status body <<<"$(token billing-service)"
T1=$(docker exec "$KC" date +%s%3N)
if [[ $status == 200 && $(claim "$body") == '["billing-readers", "billing-writers"]' ]]; then
  pass "billing-service groups = $(claim "$body")"
else
  fail "billing-service: HTTP $status, claim $( [[ $status == 200 ]] && claim "$body" || echo "$body")"
fi
CREATED=$(kcadm get users -r "$REALM" -q username=svc-billing -q exact=true --fields createdTimestamp --format csv --noquotes)
if [[ -n "$CREATED" && $CREATED -ge $T0 && $CREATED -le $T1 ]]; then
  pass "svc-billing was imported on demand during the token request (createdTimestamp $CREATED in [$T0, $T1])"
else
  fail "svc-billing createdTimestamp '$CREATED' not within token request [$T0, $T1]"
fi

echo "== 3. Local user with the same attribute is ignored"
read -r status body <<<"$(token imposter-service)"
[[ $status == 200 && $(claim "$body") == '[]' ]] && pass "imposter-service groups = []" \
  || fail "imposter-service: HTTP $status, $body"

echo "== 4. Unlinked client -> empty groups claim"
read -r status body <<<"$(token unlinked-service)"
[[ $status == 200 && $(claim "$body") == '[]' ]] && pass "unlinked-service groups = []" \
  || fail "unlinked-service: HTTP $status, $body"

echo "== 5. Ambiguous link (two LDAP users)"
read -r status body <<<"$(token dup-service)"
[[ $status == 500 && $body == *server_error* ]] && pass "dup-service rejected: $body" \
  || fail "dup-service: HTTP $status, $body"
read -r status body <<<"$(token dup-open-service)"
[[ $status == 200 && $(claim "$body") == absent ]] && pass "dup-open-service (fail open) issued token without groups claim" \
  || fail "dup-open-service: HTTP $status, $body"

echo "== 6. LDAP down -> fail closed"
docker stop "$LDAP" >/dev/null
read -r status body <<<"$(token billing-service)"
[[ $status == 500 && $body == *server_error* ]] && pass "billing-service rejected with LDAP down: $body" \
  || fail "billing-service with LDAP down: HTTP $status, $body"

echo
if [[ $failures -eq 0 ]]; then echo "ALL INTEGRATION CHECKS PASSED"; else echo "$failures CHECK(S) FAILED"; exit 1; fi
