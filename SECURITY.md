# Security Policy

This mapper decides which groups appear in access tokens and therefore what clients are
allowed to do. Bugs that let a client receive groups it should not have are treated as
security vulnerabilities.

## Supported versions

| Version | Keycloak | Supported |
|---|---|---|
| 26.8.x (`keycloak-26.8`) | 26.8.x | Yes (recommended) |
| 26.6.x (`main`) | 26.6.x | Mapper fixes only; the Keycloak 26.6 server line has unpatched upstream CVEs ([#31](https://github.com/JoWe112/kc_service-account-groups-mapper/issues/31)) |

Vulnerabilities in Keycloak itself are not fixed here: the JAR contains no Keycloak code (all
Keycloak dependencies are `provided`). Keep your Keycloak server patched, and prefer the newest
supported Keycloak line.

## Reporting a vulnerability

Do **not** open a public issue. Report privately via
[GitHub Security Advisories](https://github.com/JoWe112/kc_service-account-groups-mapper/security/advisories/new)
or by e-mail to joakim@westlund.it. Please include steps to reproduce and the affected version.

You can expect an acknowledgement within 5 working days.

## Security-relevant design

- Only users federated from the configured LDAP provider (`federationLink`) are accepted;
  local users carrying the link attribute are ignored.
- Ambiguous links (more than one LDAP user per client) and lookup failures reject the token
  request by default (fail closed).
- Error responses are generic; details are only logged server-side. No credentials or token
  contents are logged.
