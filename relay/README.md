# Aura relay

A small Cloudflare Worker that lets the Aura app use Claude without shipping the
Anthropic API key inside the (public) APK.

- `POST /v1/chat`: needs `Authorization: Bearer <Firebase ID token>` from a user
  listed in `ALLOWED_UIDS`; streams Claude's reply back unchanged.
- `POST /v1/speak`: same sign-in; turns up to 1,500 characters of text into speech with
  ElevenLabs (fixed voice and model) and returns MP3 audio.
- `GET /health`: shows only whether chat and speech are configured.

Each allowed user may make 15 chat and 15 speech requests per minute.

Optional daily cap (300 chat and 150 speech requests per user per day, set in `wrangler.toml`):
it needs a place to keep the counts. To turn it on, create a KV namespace in the Cloudflare
dashboard (Storage & Databases -> KV -> Create), copy its ID into a repository secret named
`AURA_USAGE_KV_ID`, and re-run the relay deployment. Without the secret the relay works as
before, with only the per-minute limits.

The model (`claude-sonnet-5-5`) and output limit are fixed in `wrangler.toml`, so the
app cannot request a different model or very long outputs.

With `"actions": true` in the body, the relay adds its own four `propose_*` tools
(save memory, create project, add milestone, add to-do), one per reply. They only describe a change;
the app shows it as an approval card. Tools sent by the app are ignored.

Tests: `cd relay && node --test` (no packages needed).

Deployment is automatic from `main` via `.github/workflows/relay.yml` once these
repository secrets exist: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
`ANTHROPIC_API_KEY`, `AURA_ALLOWED_UIDS`, and the existing `AURA_GOOGLE_SERVICES_JSON`
(the Firebase project ID is read from it). `ELEVENLABS_API_KEY` is optional; without it
`/v1/speak` reports that speech isn't configured.
