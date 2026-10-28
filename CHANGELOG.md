# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html), with the
major.minor taken from the Keycloak line (see README, "Versioning and releases").
This is the changelog of the Keycloak 26.8 line (`keycloak-26.8`, tags `v26.8.x`).

## [Unreleased]

### Changed
- Keycloak 26.8.x is the recommended line: Keycloak 26.6.4 has unpatched upstream CVEs with no
  community 26.6.x fix (README warning, SECURITY.md) (#31).

## [26.8.0] - 2026-10-28

First release of the Keycloak 26.8 line.

### Added
- `oidc-service-account-groups-mapper` protocol mapper ("Service account groups (via linked user)")
  for Keycloak 26.8: adds the groups of the LDAP user linked via `oauthClientID=<clientId>` to
  service account access tokens.
- `federationLink` check against the configured (or first enabled) LDAP provider.
- Bounded TTL cache per realm / LDAP provider / attribute / clientId / path mode.
- Empty list claim (`[]`) when no linked user exists; no claim only when a fail-open lookup failed.
- Configurable fail closed (`server_error`) for lookup errors and ambiguous links.
- Built against Keycloak 26.8.1 (test-only RESTEasy 6.2.19.Final); unit tests and a Docker-based
  integration test against `quay.io/keycloak/keycloak:26.8.1` + OpenLDAP (#14).
- `Keycloak-Version` manifest entry recording the Keycloak version the JAR was built for.
- Release versions follow the Keycloak line: `26.8.<patch>`, tags `v26.8.<patch>` (#16).

[Unreleased]: https://github.com/JoWe112/kc_service-account-groups-mapper/compare/v26.8.0...HEAD
[26.8.0]: https://github.com/JoWe112/kc_service-account-groups-mapper/releases/tag/v26.8.0
