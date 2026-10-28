# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html), with the
major.minor taken from the Keycloak line (see README, "Versioning and releases").
This is the changelog of the Keycloak 26.6 line (`main`, tags `v26.6.x`).

## [Unreleased]

First release of the Keycloak 26.6 line; to be tagged `v26.6.0`.

### Added
- `oidc-service-account-groups-mapper` protocol mapper ("Service account groups (via linked user)")
  for Keycloak 26.6: adds the groups of the LDAP user linked via `oauthClientID=<clientId>` to
  service account access tokens.
- `federationLink` check against the configured (or first enabled) LDAP provider.
- Bounded TTL cache per realm / LDAP provider / attribute / clientId / path mode.
- Empty list claim (`[]`) when no linked user exists; no claim only when a fail-open lookup failed.
- Configurable fail closed (`server_error`) for lookup errors and ambiguous links.
- Unit tests and a Docker-based integration test (Keycloak 26.6.4 + OpenLDAP).
- `Keycloak-Version` manifest entry recording the Keycloak version the JAR was built for.
- Release versions follow the Keycloak line: `26.6.<patch>`, tags `v26.6.<patch>` (#16).

### Changed
- Dependabot proposes only patch updates for Keycloak artifacts and the server-provided
  libraries (`jakarta.ws.rs-api`, `resteasy-core`, `jboss-logging`) to stay on Keycloak 26.6.x (#9).
