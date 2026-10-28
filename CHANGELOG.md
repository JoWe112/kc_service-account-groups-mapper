# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Build for Keycloak 26.8.x on branch `keycloak-26.8` (Keycloak 26.8.1, test-only RESTEasy
  6.2.19.Final); CI and the integration test run against `quay.io/keycloak/keycloak:26.8.1` (#14).
- `Keycloak-Version` manifest entry recording the Keycloak line the JAR was built for.

### Changed
- Dependabot proposes only patch updates for Keycloak artifacts and the server-provided
  libraries (`jakarta.ws.rs-api`, `resteasy-core`, `jboss-logging`) to stay on Keycloak 26.6.x (#9).

## [1.0.0] - 2026-10-28

### Added
- `oidc-service-account-groups-mapper` protocol mapper ("Service account groups (via linked user)")
  for Keycloak 26.6: adds the groups of the LDAP user linked via `oauthClientID=<clientId>` to
  service account access tokens.
- `federationLink` check against the configured (or first enabled) LDAP provider.
- Bounded TTL cache per realm / LDAP provider / attribute / clientId / path mode.
- Empty list claim (`[]`) when no linked user exists; no claim only when a fail-open lookup failed.
- Configurable fail closed (`server_error`) for lookup errors and ambiguous links.
- Unit tests and a Docker-based integration test (Keycloak 26.6.4 + OpenLDAP).
