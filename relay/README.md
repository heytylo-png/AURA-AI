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

The newest user message may instead be a list of blocks: one base64 image (JPEG, PNG, WebP or
GIF, at most 4,000,000 base64 characters) followed by an optional text block. The relay rebuilds
the blocks from those fields only; images anywhere else, image URLs and file references are refused.

With `"web": true` (the user's "Let Claude search the web" switch), the relay adds Anthropic's
web search tool, capped at `WEB_SEARCH_MAX_USES` searches per reply (default 3). Searches run on
Anthropic's side and are billed per search (about $10 per 1,000). Replies with web search have
their own daily cap, `DAILY_SEARCH_LIMIT` (default 100 per user), when the daily-cap store is set
up. Web search must also be allowed for the organization in the Claude Console, or such requests
fail with an error from the Claude API.

With `"fetch": true` (the user's "Let Claude open links you send" switch, sent only when the chat
contains a link), the relay adds Anthropic's web fetch tool, capped at `WEB_FETCH_MAX_USES` pages per
reply (default 3) and 20,000 tokens of text per page. It can open only addresses already in the
conversation, and costs only the tokens of the text it reads. Web fetch must also be allowed for
the organization in the Claude Console.

Images and videos (`src/media.js`): with `"media": true` (the user's "Let Claude create images and
videos" switch) and an `OPENAI_API_KEY`, Claude gets two tools, `generate_image` and
`generate_video`. The app then calls `POST /v1/image` (answered with a JPEG) or `POST /v1/video`
(answered with a job ID and a ticket), then `GET /v1/video/status` and `GET /v1/video/content`. The
model, quality, size and longest video are set here (`OPENAI_IMAGE_MODEL`, `OPENAI_IMAGE_QUALITY`,
`OPENAI_VIDEO_MODEL`, `VIDEO_MAX_SECONDS`), not by the app. A ticket is an HMAC of the user and the
video ID, so only the user who started a video can check on it or download it. The daily caps are
`DAILY_IMAGE_LIMIT` (default 20) and `DAILY_VIDEO_LIMIT` (default 3), and `MEDIA_LIMITER` allows 5
creations a minute; checking on a video doesn't count toward either. OpenAI is sent a hash of the
user's ID, never the ID itself, for its abuse monitoring.

Tests: `cd relay && node --test` (no packages needed).

Deployment is automatic from `main` via `.github/workflows/relay.yml` once these
repository secrets exist: `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`,
`ANTHROPIC_API_KEY`, `AURA_ALLOWED_UIDS`, and the existing `AURA_GOOGLE_SERVICES_JSON`
(the Firebase project ID is read from it). `ELEVENLABS_API_KEY` is optional; without it
`/v1/speak` reports that speech isn't configured. `OPENAI_API_KEY` is optional too; without it the
image and video endpoints report that they aren't configured, and Claude isn't offered the tools.
