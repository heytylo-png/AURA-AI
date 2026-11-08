# Aura relay

A small Cloudflare Worker that lets the Aura app use Claude without shipping the
Anthropic API key inside the (public) APK.

- `POST /v1/chat`: needs `Authorization: Bearer <Firebase ID token>` from a user
  listed in `ALLOWED_UIDS`; streams Claude's reply back unchanged.
- `GET /health`: shows whether the relay is configured; never shows secrets.

The model (`claude-sonnet-5-5`) and output limit are fixed in `wrangler.toml`, so the
app cannot request a different model or very long outputs.

With `"actions": true` in the body, the relay adds its own three `propose_*` tools
(save memory, create project, add milestone), one per reply. They only describe a change;
the app shows it as an approval card. Tools sent by the app are ignored.

Tests: `cd relay && node --test` (no packages needed).

Deployment is automatic from `main` via `.github/workflows/relay.yml` once these
repository secrets exist: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
`ANTHROPIC_API_KEY`, `AURA_ALLOWED_UIDS`, and the existing `AURA_GOOGLE_SERVICES_JSON`
(the Firebase project ID is read from it).
