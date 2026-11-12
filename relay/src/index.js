// Aura relay: lets the Android app talk to Claude without putting the Anthropic API key in the app.
//
// POST /v1/chat   Authorization: Bearer <Firebase ID token>
//   body: { "system": "...", "messages": [{ "role": "user" | "assistant", "content": "..." }] }
//   response: Anthropic Messages API server-sent events, passed through unchanged.
// POST /v1/speak  same sign-in; body: { "text": "..." }; response: MP3 audio from ElevenLabs.
// GET  /health    no auth; reports only whether chat and speech are configured.
//
// Only Firebase users listed in ALLOWED_UIDS may call /v1/chat. The model, output limit and
// request size are fixed here, so a stolen token cannot pick an expensive model or huge outputs.
//
// Environment:
//   ANTHROPIC_API_KEY   secret
//   ALLOWED_UIDS        secret, comma-separated Firebase user IDs ("access IDs" shown in Aura's Settings)
//   FIREBASE_PROJECT_ID var, the Firebase project the app signs in to
//   ANTHROPIC_MODEL     var, default claude-sonnet-5-5
//   MAX_OUTPUT_TOKENS   var, default 2048
//   DAILY_CHAT_LIMIT    var, default 300: chat requests per user per day (needs the USAGE binding)
//   DAILY_SPEAK_LIMIT   var, default 150: speech requests per user per day (needs the USAGE binding)
//   USAGE               optional KV namespace that holds the daily counts

const JWKS_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const ANTHROPIC_URL = "https://api.anthropic.com/v1/messages";
const ANTHROPIC_VERSION = "2023-06-01";
const DEFAULT_MODEL = "claude-sonnet-5-5";
const DEFAULT_MAX_OUTPUT_TOKENS = 2048;
const MAX_MESSAGES = 40;
const MAX_TOTAL_CHARS = 100_000;
// Room for a shared project's notes (the app sends at most ~18k characters of notes).
const MAX_SYSTEM_CHARS = 32_000;
const CLOCK_SKEW_SECONDS = 60;

// Changes Claude may PROPOSE. Defined here, never taken from the app, so a modified app cannot
// add tools. They only describe a change; the app shows it as a card and nothing happens until
// the user taps Approve on the phone.
export const MEMORY_CATEGORIES = ["PREFERENCE", "FACT", "GOAL", "CONTEXT", "IDENTITY_RULE"];
const REASON = { type: "string", maxLength: 300, description: "One short sentence: why this helps the user." };
export const PROPOSAL_TOOLS = [
  {
    name: "propose_save_memory",
    description:
      "Suggest saving one durable fact or preference the user stated, so Aura remembers it. " +
      "Only for things the user said about themselves; the user must approve it in the app.",
    input_schema: {
      type: "object",
      properties: {
        text: { type: "string", minLength: 1, maxLength: 500, description: "The memory, in the user's terms." },
        category: { type: "string", enum: MEMORY_CATEGORIES },
        importance: { type: "integer", minimum: 1, maximum: 5 },
        reason: REASON,
      },
      required: ["text", "category", "importance", "reason"],
    },
  },
  {
    name: "propose_create_project",
    description: "Suggest creating a project in the user's Project Library. The user must approve it in the app.",
    input_schema: {
      type: "object",
      properties: {
        title: { type: "string", minLength: 1, maxLength: 80 },
        description: { type: "string", maxLength: 1000 },
        reason: REASON,
      },
      required: ["title", "description", "reason"],
    },
  },
  {
    name: "propose_add_milestone",
    description:
      "Suggest adding a milestone to one of the user's projects. You cannot see their projects; use the project " +
      "name the user gave. The user picks the project and approves it in the app.",
    input_schema: {
      type: "object",
      properties: {
        project_title: { type: "string", maxLength: 80 },
        milestone_title: { type: "string", minLength: 1, maxLength: 120 },
        reason: REASON,
      },
      required: ["project_title", "milestone_title", "reason"],
    },
  },
  {
    name: "propose_add_todo",
    description:
      "Suggest adding a to-do to the user's list. You cannot see their existing to-dos or projects; use the " +
      "project name the user gave, if any. Due dates are dates only (no reminders). The user approves it in the app.",
    input_schema: {
      type: "object",
      properties: {
        title: { type: "string", minLength: 1, maxLength: 200 },
        notes: { type: "string", maxLength: 2000 },
        due_date: { type: "string", pattern: "^\\d{4}-\\d{2}-\\d{2}$", maxLength: 10, description: "YYYY-MM-DD, only if the user gave a date." },
        project_title: { type: "string", maxLength: 80 },
        reason: REASON,
      },
      required: ["title", "reason"],
    },
  },
];

// Speech (ElevenLabs). The key is a relay secret; voice, model and the text limit are fixed here,
// so the app can't pick another voice or send long texts that burn credits.
const ELEVENLABS_URL = "https://api.elevenlabs.io/v1/text-to-speech";
export const MAX_SPEECH_CHARS = 1500;
const DEFAULT_TTS_MODEL = "eleven_flash_v2_5";

/**
 * Per-user request limits (Cloudflare rate-limiting bindings, set in wrangler.toml), a guard against
 * a runaway loop or a stolen token burning credits. If a binding is missing or errors, the request
 * is allowed (fail open): the Anthropic/ElevenLabs spend limits are the hard backstop.
 */
export async function withinLimit(limiter, uid) {
  if (!limiter || typeof limiter.limit !== "function") return true;
  try {
    const { success } = await limiter.limit({ key: uid });
    return success !== false;
  } catch {
    return true;
  }
}

export const DEFAULT_DAILY_CHAT_LIMIT = 300;
export const DEFAULT_DAILY_SPEAK_LIMIT = 150;

/**
 * Per-user daily cap, counted in the USAGE key-value store under "<kind>:<user>:<UTC date>".
 * The per-minute limits alone still allow about 21,600 requests a day; this stops a stolen token
 * or a runaway loop long before that. Returns false once the day's count has reached [limit].
 *
 * Like the per-minute limits it fails open: without the binding, or if the store errors, the
 * request is allowed, because the Anthropic/ElevenLabs spend limits are the hard backstop. The
 * store is eventually consistent, so a burst of simultaneous requests can overshoot by a few.
 */
export async function withinDailyLimit(store, kind, uid, limit, nowMs) {
  if (!store || typeof store.get !== "function" || typeof store.put !== "function") return true;
  if (!Number.isFinite(limit) || limit <= 0) return true;
  const key = `${kind}:${uid}:${new Date(nowMs).toISOString().slice(0, 10)}`;
  try {
    const used = Number(await store.get(key)) || 0;
    if (used >= limit) return false;
    // Kept for two days, then the store removes it by itself.
    await store.put(key, String(used + 1), { expirationTtl: 2 * 86400 });
    return true;
  } catch {
    return true;
  }
}

export class RelayError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

function json(status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json", "cache-control": "no-store" },
  });
}

function errorResponse(e) {
  return json(e.status, { error: { type: e.code, message: e.message } });
}

function base64UrlToBytes(s) {
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((s.length + 3) % 4);
  const bin = atob(b64);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

function decodeJsonPart(part) {
  return JSON.parse(new TextDecoder().decode(base64UrlToBytes(part)));
}

// A token naming a key we don't have may mean Google rotated its keys early, so the keys are
// fetched again, but at most this often. Without the pause, anyone could make the relay download
// the keys on every request just by sending made-up key IDs (audit 03, A3-04).
export const UNKNOWN_KEY_REFETCH_MS = 5 * 60 * 1000;

/** Caches Google's signing keys for Firebase ID tokens, honouring Cache-Control max-age. */
export function createKeyStore(fetchImpl, now) {
  let cache = { keys: null, expiresAt: 0, fetchedAt: 0 };
  return async function getKey(kid) {
    const stale = !cache.keys || now() >= cache.expiresAt;
    const unknownKeyRetry = !stale && !cache.keys.has(kid) && now() - cache.fetchedAt >= UNKNOWN_KEY_REFETCH_MS;
    if (stale || unknownKeyRetry) {
      const res = await fetchImpl(JWKS_URL);
      if (!res.ok) throw new RelayError(503, "auth_unavailable", "Could not fetch sign-in verification keys.");
      const maxAge = Number(/max-age=(\d+)/.exec(res.headers.get("cache-control") || "")?.[1] || 3600);
      const body = await res.json();
      const keys = new Map();
      for (const jwk of body.keys || []) {
        const key = await crypto.subtle.importKey(
          "jwk",
          { kty: jwk.kty, n: jwk.n, e: jwk.e, alg: "RS256", ext: true },
          { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
          false,
          ["verify"]
        );
        keys.set(jwk.kid, key);
      }
      cache = { keys, expiresAt: now() + Math.min(maxAge, 86400) * 1000, fetchedAt: now() };
    }
    return cache.keys.get(kid) || null;
  };
}

/** Verifies a Firebase ID token and returns its user ID. Throws RelayError(401) otherwise. */
export async function verifyFirebaseIdToken(token, projectId, getKey, now) {
  const parts = (token || "").split(".");
  if (parts.length !== 3) throw new RelayError(401, "unauthenticated", "Missing or malformed sign-in token.");
  let header, payload;
  try {
    header = decodeJsonPart(parts[0]);
    payload = decodeJsonPart(parts[1]);
  } catch {
    throw new RelayError(401, "unauthenticated", "Malformed sign-in token.");
  }
  if (header.alg !== "RS256" || !header.kid) throw new RelayError(401, "unauthenticated", "Unsupported sign-in token.");

  const key = await getKey(header.kid);
  if (!key) throw new RelayError(401, "unauthenticated", "Unknown sign-in token key.");
  const valid = await crypto.subtle.verify(
    "RSASSA-PKCS1-v1_5",
    key,
    base64UrlToBytes(parts[2]),
    new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
  );
  if (!valid) throw new RelayError(401, "unauthenticated", "Invalid sign-in token signature.");

  const t = Math.floor(now() / 1000);
  if (payload.aud !== projectId) throw new RelayError(401, "unauthenticated", "Sign-in token is for a different project.");
  if (payload.iss !== `https://securetoken.google.com/${projectId}`) throw new RelayError(401, "unauthenticated", "Sign-in token has the wrong issuer.");
  if (typeof payload.exp !== "number" || payload.exp + CLOCK_SKEW_SECONDS < t) throw new RelayError(401, "token_expired", "Sign-in token has expired.");
  if (typeof payload.iat !== "number" || payload.iat - CLOCK_SKEW_SECONDS > t) throw new RelayError(401, "unauthenticated", "Sign-in token is not yet valid.");
  if (typeof payload.sub !== "string" || payload.sub.length === 0 || payload.sub.length > 128) throw new RelayError(401, "unauthenticated", "Sign-in token has no user.");
  return payload.sub;
}

/** Validates and normalises the app's request body. Throws RelayError(400). */
export function validateChatBody(body) {
  if (!body || typeof body !== "object") throw new RelayError(400, "invalid_request", "Body must be a JSON object.");
  const { system, messages } = body;
  if (system !== undefined && (typeof system !== "string" || system.length > MAX_SYSTEM_CHARS)) {
    throw new RelayError(400, "invalid_request", `system must be a string of at most ${MAX_SYSTEM_CHARS} characters.`);
  }
  if (!Array.isArray(messages) || messages.length === 0 || messages.length > MAX_MESSAGES) {
    throw new RelayError(400, "invalid_request", `messages must contain 1 to ${MAX_MESSAGES} items.`);
  }
  let total = 0;
  const clean = messages.map((m, i) => {
    if (!m || (m.role !== "user" && m.role !== "assistant") || typeof m.content !== "string" || m.content.trim() === "") {
      throw new RelayError(400, "invalid_request", `messages[${i}] needs role user/assistant and non-empty text content.`);
    }
    total += m.content.length;
    return { role: m.role, content: m.content };
  });
  if (total > MAX_TOTAL_CHARS) throw new RelayError(413, "request_too_large", "Conversation is too long for one request.");
  if (clean[0].role !== "user" || clean[clean.length - 1].role !== "user") {
    throw new RelayError(400, "invalid_request", "The conversation must start and end with a user message.");
  }
  if (body.actions !== undefined && typeof body.actions !== "boolean") {
    throw new RelayError(400, "invalid_request", "actions must be true or false.");
  }
  // Anything else the app sends (model, tools, max_tokens...) is ignored.
  return { system: system || undefined, messages: clean, actions: body.actions === true };
}

export function createHandler({ fetchImpl = (...a) => fetch(...a), now = () => Date.now() } = {}) {
  const getKey = createKeyStore(fetchImpl, now);

  return async function handle(request, env) {
    const url = new URL(request.url);
    const configured = Boolean(env.ANTHROPIC_API_KEY && env.FIREBASE_PROJECT_ID);

    if (request.method === "GET" && url.pathname === "/health") {
      // Public, so it says only whether chat and speech are set up: not the model or how many users.
      return json(200, {
        ok: configured,
        speech: Boolean(env.ELEVENLABS_API_KEY && env.ELEVENLABS_API_KEY !== "not-set" && env.ELEVENLABS_VOICE_ID),
      });
    }
    if (url.pathname !== "/v1/chat" && url.pathname !== "/v1/speak") {
      return json(404, { error: { type: "not_found", message: "Not found." } });
    }
    if (request.method !== "POST") return json(405, { error: { type: "method_not_allowed", message: "Use POST." } });

    // Same sign-in and allowlist for every endpoint.
    async function authorize() {
      if (!env.FIREBASE_PROJECT_ID) throw new RelayError(503, "relay_not_configured", "The relay is missing its Firebase project setting.");
      const auth = request.headers.get("authorization") || "";
      const token = auth.startsWith("Bearer ") ? auth.slice(7) : "";
      const uid = await verifyFirebaseIdToken(token, env.FIREBASE_PROJECT_ID, getKey, now);
      const allowed = new Set((env.ALLOWED_UIDS || "").split(",").map((s) => s.trim()).filter(Boolean));
      if (!allowed.has(uid)) {
        throw new RelayError(403, "user_not_allowed", `This access ID is not allowed to use the relay yet: ${uid}`);
      }
      return uid;
    }

    async function checkLimit(limiter, uid, kind, dailyLimit) {
      if (!(await withinLimit(limiter, uid))) {
        throw new RelayError(429, "rate_limited", "Too many requests in the last minute. Wait a moment and try again.");
      }
      if (!(await withinDailyLimit(env.USAGE, kind, uid, dailyLimit, now()))) {
        throw new RelayError(429, "daily_limit", `Today's limit of ${dailyLimit} ${kind} requests is used up. It resets at midnight UTC.`);
      }
    }

    if (url.pathname === "/v1/speak") {
      try {
        const uid = await authorize();
        await checkLimit(env.SPEAK_LIMITER, uid, "speech", Number(env.DAILY_SPEAK_LIMIT) || DEFAULT_DAILY_SPEAK_LIMIT);
        const key = env.ELEVENLABS_API_KEY;
        if (!key || key === "not-set" || !env.ELEVENLABS_VOICE_ID) {
          throw new RelayError(503, "tts_not_configured", "The relay has no ElevenLabs key or voice yet.");
        }
        let body;
        try {
          body = await request.json();
        } catch {
          throw new RelayError(400, "invalid_request", "Body must be valid JSON.");
        }
        const text = typeof body?.text === "string" ? body.text.trim() : "";
        if (!text) throw new RelayError(400, "invalid_request", "text must be a non-empty string.");
        if (text.length > MAX_SPEECH_CHARS) {
          throw new RelayError(413, "request_too_large", `text must be at most ${MAX_SPEECH_CHARS} characters.`);
        }
        const upstream = await fetchImpl(
          `${ELEVENLABS_URL}/${encodeURIComponent(env.ELEVENLABS_VOICE_ID)}/stream?output_format=mp3_44100_128`,
          {
            method: "POST",
            headers: { "content-type": "application/json", "xi-api-key": key, accept: "audio/mpeg" },
            body: JSON.stringify({ text, model_id: env.ELEVENLABS_MODEL || DEFAULT_TTS_MODEL }),
          }
        );
        if (!upstream.ok) {
          let detail = `ElevenLabs returned HTTP ${upstream.status}.`;
          try {
            const e = await upstream.json();
            const msg = e?.detail?.message || e?.detail?.status || (typeof e?.detail === "string" ? e.detail : null);
            if (msg) detail = `ElevenLabs: ${msg}`;
          } catch {}
          // A bad key is the relay's problem, not the user's.
          const status = upstream.status === 401 ? 502 : upstream.status;
          throw new RelayError(status, "tts_upstream_error", detail);
        }
        return new Response(upstream.body, {
          status: 200,
          headers: { "content-type": "audio/mpeg", "cache-control": "no-store" },
        });
      } catch (e) {
        if (e instanceof RelayError) return errorResponse(e);
        return errorResponse(new RelayError(500, "relay_error", "The relay failed unexpectedly."));
      }
    }

    try {
      if (!configured) throw new RelayError(503, "relay_not_configured", "The relay is missing its Anthropic key or Firebase project setting.");
      const uid = await authorize();
      await checkLimit(env.CHAT_LIMITER, uid, "chat", Number(env.DAILY_CHAT_LIMIT) || DEFAULT_DAILY_CHAT_LIMIT);

      let body;
      try {
        body = await request.json();
      } catch {
        throw new RelayError(400, "invalid_request", "Body must be valid JSON.");
      }
      const chat = validateChatBody(body);

      const upstream = await fetchImpl(ANTHROPIC_URL, {
        method: "POST",
        headers: {
          "content-type": "application/json",
          "x-api-key": env.ANTHROPIC_API_KEY,
          "anthropic-version": ANTHROPIC_VERSION,
        },
        body: JSON.stringify({
          model: env.ANTHROPIC_MODEL || DEFAULT_MODEL,
          max_tokens: Number(env.MAX_OUTPUT_TOKENS) || DEFAULT_MAX_OUTPUT_TOKENS,
          ...(chat.system ? { system: chat.system } : {}),
          messages: chat.messages,
          ...(chat.actions
            ? { tools: PROPOSAL_TOOLS, tool_choice: { type: "auto", disable_parallel_tool_use: true } }
            : {}),
          stream: true,
        }),
      });

      if (!upstream.ok) {
        let detail = `Claude API returned HTTP ${upstream.status}.`;
        try {
          const e = await upstream.json();
          if (e?.error?.message) detail = `Claude API: ${e.error.message}`;
        } catch {}
        // 401/403 from Anthropic mean the relay's key is wrong; report as a relay problem, not the user's.
        const status = upstream.status === 401 || upstream.status === 403 ? 502 : upstream.status;
        throw new RelayError(status, "upstream_error", detail);
      }

      return new Response(upstream.body, {
        status: 200,
        headers: { "content-type": "text/event-stream", "cache-control": "no-store" },
      });
    } catch (e) {
      if (e instanceof RelayError) return errorResponse(e);
      return errorResponse(new RelayError(500, "relay_error", "The relay failed unexpectedly."));
    }
  };
}

const handle = createHandler();
export default { fetch: (request, env) => handle(request, env) };
