// Run with: node --test relay/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHandler, createKeyStore, validateChatBody, RelayError, PROPOSAL_TOOLS, MEMORY_CATEGORIES, UNKNOWN_KEY_REFETCH_MS } from "../src/index.js";

const PROJECT = "aura-test-project";
const NOW = Date.UTC(2026, 10, 5, 12, 0, 0);
const ALLOWED = "user-allowed";

const keyPair = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"]
);
const otherKeyPair = await crypto.subtle.generateKey(
  { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
  true,
  ["sign", "verify"]
);
const publicJwk = { ...(await crypto.subtle.exportKey("jwk", keyPair.publicKey)), kid: "k1", alg: "RS256", use: "sig" };

const b64url = (bytes) => Buffer.from(bytes).toString("base64url");
const enc = (obj) => b64url(new TextEncoder().encode(JSON.stringify(obj)));

async function makeToken(claims = {}, { kid = "k1", key = keyPair.privateKey, alg = "RS256" } = {}) {
  const t = Math.floor(NOW / 1000);
  const payload = {
    iss: `https://securetoken.google.com/${PROJECT}`,
    aud: PROJECT,
    sub: ALLOWED,
    iat: t - 10,
    exp: t + 3600,
    auth_time: t - 10,
    ...claims,
  };
  const signingInput = `${enc({ alg, kid, typ: "JWT" })}.${enc(payload)}`;
  const sig = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(signingInput));
  return `${signingInput}.${b64url(new Uint8Array(sig))}`;
}

function setup({ upstream, speechUpstream } = {}) {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url: String(url), init });
    if (String(url).includes("googleapis.com")) {
      return new Response(JSON.stringify({ keys: [publicJwk] }), {
        headers: { "content-type": "application/json", "cache-control": "public, max-age=1000" },
      });
    }
    if (String(url).startsWith("https://api.elevenlabs.io/")) {
      return speechUpstream ? speechUpstream(init) : new Response(new Uint8Array([0x49, 0x44, 0x33]), { headers: { "content-type": "audio/mpeg" } });
    }
    if (String(url) === "https://api.anthropic.com/v1/messages") {
      return upstream ? upstream(init) : new Response("event: message_stop\ndata: {}\n\n", { headers: { "content-type": "text/event-stream" } });
    }
    throw new Error(`unexpected fetch ${url}`);
  };
  const handle = createHandler({ fetchImpl, now: () => NOW });
  const env = { ANTHROPIC_API_KEY: "sk-test", FIREBASE_PROJECT_ID: PROJECT, ALLOWED_UIDS: `someone-else, ${ALLOWED}` };
  return { handle, env, calls };
}

const chatBody = { system: "You are Aura.", messages: [{ role: "user", content: "Hello" }] };

async function post(handle, env, { token, body = chatBody } = {}) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  return handle(new Request("https://relay.example/v1/chat", { method: "POST", headers, body: JSON.stringify(body) }), env);
}

test("valid token from an allowed user streams Claude's response with server-fixed settings", async () => {
  const { handle, env, calls } = setup();
  const res = await post(handle, env, { token: await makeToken() });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("content-type"), "text/event-stream");
  assert.match(await res.text(), /message_stop/);

  const upstream = calls.find((c) => c.url.startsWith("https://api.anthropic.com"));
  assert.equal(upstream.init.headers["x-api-key"], "sk-test");
  assert.equal(upstream.init.headers["anthropic-version"], "2023-06-01");
  const sent = JSON.parse(upstream.init.body);
  assert.equal(sent.model, "claude-sonnet-5-5");
  assert.equal(sent.max_tokens, 2048);
  assert.equal(sent.stream, true);
  assert.equal(sent.system, "You are Aura.");
  assert.deepEqual(sent.messages, [{ role: "user", content: "Hello" }]);
});

test("the app cannot choose the model or output size", async () => {
  const { handle, env, calls } = setup();
  await post(handle, env, {
    token: await makeToken(),
    body: { ...chatBody, model: "claude-opus-5-5", max_tokens: 64000 },
  });
  const sent = JSON.parse(calls.find((c) => c.url.startsWith("https://api.anthropic.com")).init.body);
  assert.equal(sent.model, "claude-sonnet-5-5");
  assert.equal(sent.max_tokens, 2048);
});

test("requests without a token, or with a bad one, never reach Claude", async () => {
  const { handle, env, calls } = setup();
  const cases = [
    [undefined, 401],
    ["not-a-jwt", 401],
    [await makeToken({}, { key: otherKeyPair.privateKey }), 401], // forged signature
    [await makeToken({}, { kid: "unknown" }), 401],
    [await makeToken({}, { alg: "HS256" }), 401],
    [await makeToken({ aud: "other-project" }), 401],
    [await makeToken({ iss: "https://securetoken.google.com/other-project" }), 401],
    [await makeToken({ exp: Math.floor(NOW / 1000) - 3600 }), 401], // expired
    [await makeToken({ iat: Math.floor(NOW / 1000) + 3600 }), 401], // issued in the future
    [await makeToken({ sub: "" }), 401],
  ];
  for (const [token, status] of cases) {
    const res = await post(handle, env, { token });
    assert.equal(res.status, status, `token ${String(token).slice(0, 20)} -> ${res.status}`);
  }
  assert.equal(calls.filter((c) => c.url.startsWith("https://api.anthropic.com")).length, 0);
});

test("a valid user who is not on the allowlist gets 403 with their access ID", async () => {
  const { handle, env, calls } = setup();
  const res = await post(handle, env, { token: await makeToken({ sub: "stranger-123" }) });
  assert.equal(res.status, 403);
  const body = await res.json();
  assert.equal(body.error.type, "user_not_allowed");
  assert.match(body.error.message, /stranger-123/);
  assert.equal(calls.filter((c) => c.url.startsWith("https://api.anthropic.com")).length, 0);
});

test("an empty allowlist lets nobody in", async () => {
  const { handle, env } = setup();
  const res = await post(handle, { ...env, ALLOWED_UIDS: "" }, { token: await makeToken() });
  assert.equal(res.status, 403);
});

test("missing configuration is reported, not hidden", async () => {
  const { handle, env } = setup();
  const res = await post(handle, { ...env, ANTHROPIC_API_KEY: "" }, { token: await makeToken() });
  assert.equal(res.status, 503);
  assert.equal((await res.json()).error.type, "relay_not_configured");
});

test("Claude API errors are passed back with their message; a bad relay key becomes 502", async () => {
  const bad = (status, message) => () =>
    new Response(JSON.stringify({ type: "error", error: { type: "x", message } }), { status, headers: { "content-type": "application/json" } });

  let { handle, env } = setup({ upstream: bad(429, "Rate limited") });
  let res = await post(handle, env, { token: await makeToken() });
  assert.equal(res.status, 429);
  assert.match((await res.json()).error.message, /Rate limited/);

  ({ handle, env } = setup({ upstream: bad(401, "invalid x-api-key") }));
  res = await post(handle, env, { token: await makeToken() });
  assert.equal(res.status, 502);
  assert.match((await res.json()).error.message, /invalid x-api-key/);
});

test("signing keys are fetched once and cached", async () => {
  const { handle, env, calls } = setup();
  await post(handle, env, { token: await makeToken() });
  await post(handle, env, { token: await makeToken() });
  assert.equal(calls.filter((c) => c.url.includes("googleapis.com")).length, 1);
});

test("health reports configuration without secrets", async () => {
  const { handle, env } = setup();
  const res = await handle(new Request("https://relay.example/health"), env);
  const body = await res.json();
  assert.deepEqual(body, { ok: true, speech: false });
  assert.ok(!JSON.stringify(body).includes("sk-test"));
});

test("other paths and methods are rejected", async () => {
  const { handle, env } = setup();
  assert.equal((await handle(new Request("https://relay.example/v1/other", { method: "POST" }), env)).status, 404);
  assert.equal((await handle(new Request("https://relay.example/v1/chat"), env)).status, 405);
});

test("request bodies are validated", () => {
  const bad = [
    null,
    { messages: [] },
    { messages: [{ role: "system", content: "x" }] },
    { messages: [{ role: "user", content: "   " }] },
    { messages: [{ role: "assistant", content: "hi" }] },
    { messages: [{ role: "user", content: "a" }, { role: "assistant", content: "b" }] },
    { messages: Array.from({ length: 41 }, () => ({ role: "user", content: "x" })) },
    { system: "x".repeat(32_001), messages: [{ role: "user", content: "x" }] },
    { messages: [{ role: "user", content: "x".repeat(100_001) }] },
  ];
  for (const b of bad) assert.throws(() => validateChatBody(b), RelayError);

  assert.deepEqual(
    validateChatBody({ messages: [{ role: "user", content: "a", extra: 1 }, { role: "assistant", content: "b" }, { role: "user", content: "c" }] }),
    { system: undefined, messages: [{ role: "user", content: "a" }, { role: "assistant", content: "b" }, { role: "user", content: "c" }], actions: false }
  );
  assert.throws(() => validateChatBody({ messages: [{ role: "user", content: "x" }], actions: "yes" }), RelayError);
});

test("proposal tools are added only when the app asks, and only the relay's own", async () => {
  const { handle, env, calls } = setup();
  const upstreamBodies = () =>
    calls.filter((c) => c.url.startsWith("https://api.anthropic.com")).map((c) => JSON.parse(c.init.body));

  await post(handle, env, { token: await makeToken() });
  await post(handle, env, {
    token: await makeToken(),
    body: { ...chatBody, actions: true, tools: [{ name: "delete_everything", input_schema: { type: "object" } }] },
  });
  await post(handle, env, {
    token: await makeToken(),
    body: { ...chatBody, tools: [{ name: "delete_everything", input_schema: { type: "object" } }] },
  });

  const [plain, withActions, sneaky] = upstreamBodies();
  assert.equal(plain.tools, undefined, "no tools unless asked");
  assert.deepEqual(
    withActions.tools.map((t) => t.name),
    ["propose_save_memory", "propose_create_project", "propose_add_milestone", "propose_add_todo"]
  );
  assert.deepEqual(withActions.tool_choice, { type: "auto", disable_parallel_tool_use: true });
  assert.equal(sneaky.tools, undefined, "tools sent by the app are ignored");
});

test("proposal tools are tightly bounded", () => {
  for (const tool of PROPOSAL_TOOLS) {
    assert.match(tool.name, /^propose_/);
    assert.ok(tool.input_schema.required.includes("reason"));
    for (const [name, prop] of Object.entries(tool.input_schema.properties)) {
      if (prop.type === "string" && !prop.enum) assert.ok(prop.maxLength > 0, `${tool.name}.${name} needs a maxLength`);
    }
  }
  const memory = PROPOSAL_TOOLS.find((t) => t.name === "propose_save_memory");
  assert.deepEqual(memory.input_schema.properties.category.enum, MEMORY_CATEGORIES);
  assert.deepEqual(memory.input_schema.properties.importance, { type: "integer", minimum: 1, maximum: 5 });
});

const speakEnv = (env) => ({ ...env, ELEVENLABS_API_KEY: "el-test", ELEVENLABS_VOICE_ID: "wsj349S7Ee6gB32Poaf0" });

async function speak(handle, env, { token, body = { text: "Hello there." } } = {}) {
  const headers = { "content-type": "application/json" };
  if (token) headers.authorization = `Bearer ${token}`;
  return handle(new Request("https://relay.example/v1/speak", { method: "POST", headers, body: JSON.stringify(body) }), env);
}

test("speech: allowed user gets audio in the relay's fixed voice and model", async () => {
  const { handle, env, calls } = setup();
  const res = await speak(handle, speakEnv(env), { token: await makeToken(), body: { text: " Hello there. ", voice_id: "someone-else", model_id: "x" } });
  assert.equal(res.status, 200);
  assert.equal(res.headers.get("content-type"), "audio/mpeg");
  const up = calls.find((c) => c.url.startsWith("https://api.elevenlabs.io/"));
  assert.equal(up.url, "https://api.elevenlabs.io/v1/text-to-speech/wsj349S7Ee6gB32Poaf0/stream?output_format=mp3_44100_128");
  assert.equal(up.init.headers["xi-api-key"], "el-test");
  assert.deepEqual(JSON.parse(up.init.body), { text: "Hello there.", model_id: "eleven_flash_v2_5" });
});

test("speech: no token, not allowed, too long, or no key never reaches ElevenLabs", async () => {
  const { handle, env, calls } = setup();
  assert.equal((await speak(handle, speakEnv(env), {})).status, 401);
  assert.equal((await speak(handle, speakEnv(env), { token: await makeToken({ sub: "stranger" }) })).status, 403);
  assert.equal((await speak(handle, speakEnv(env), { token: await makeToken(), body: { text: "x".repeat(1501) } })).status, 413);
  assert.equal((await speak(handle, speakEnv(env), { token: await makeToken(), body: { text: "  " } })).status, 400);
  const noKey = await speak(handle, { ...speakEnv(env), ELEVENLABS_API_KEY: "not-set" }, { token: await makeToken() });
  assert.equal(noKey.status, 503);
  assert.equal((await noKey.json()).error.type, "tts_not_configured");
  assert.equal(calls.filter((c) => c.url.startsWith("https://api.elevenlabs.io/")).length, 0);
});

test("speech: ElevenLabs errors come back readable; a bad key becomes 502", async () => {
  const quota = () => new Response(JSON.stringify({ detail: { status: "quota_exceeded", message: "You have 12 credits left." } }), { status: 402 });
  let { handle, env } = setup({ speechUpstream: quota });
  let res = await speak(handle, speakEnv(env), { token: await makeToken() });
  assert.equal(res.status, 402);
  assert.match((await res.json()).error.message, /12 credits/);

  ({ handle, env } = setup({ speechUpstream: () => new Response(JSON.stringify({ detail: "Invalid API key" }), { status: 401 }) }));
  res = await speak(handle, speakEnv(env), { token: await makeToken() });
  assert.equal(res.status, 502);
});

// A fake Cloudflare rate limiter: allows `max` calls per key.
function fakeLimiter(max) {
  const counts = new Map();
  return {
    keys: counts,
    async limit({ key }) {
      const n = (counts.get(key) || 0) + 1;
      counts.set(key, n);
      return { success: n <= max };
    },
  };
}

test("rate limit: chat and speech are limited per user and over-limit calls never reach upstream", async () => {
  const { handle, env, calls } = setup();
  const chatLimiter = fakeLimiter(2);
  const speakLimiter = fakeLimiter(1);
  const limited = { ...speakEnv(env), CHAT_LIMITER: chatLimiter, SPEAK_LIMITER: speakLimiter };
  const token = await makeToken();

  assert.equal((await post(handle, limited, { token })).status, 200);
  assert.equal((await post(handle, limited, { token })).status, 200);
  const third = await post(handle, limited, { token });
  assert.equal(third.status, 429);
  assert.equal((await third.json()).error.type, "rate_limited");
  assert.equal(calls.filter((c) => c.url.startsWith("https://api.anthropic.com")).length, 2);
  assert.deepEqual([...chatLimiter.keys.keys()], [ALLOWED]);

  assert.equal((await speak(handle, limited, { token })).status, 200);
  assert.equal((await speak(handle, limited, { token })).status, 429);
  assert.equal(calls.filter((c) => c.url.startsWith("https://api.elevenlabs.io")).length, 1);
});

test("rate limit: unauthenticated calls don't count, and a missing or broken limiter fails open", async () => {
  const { handle, env } = setup();
  const chatLimiter = fakeLimiter(0);
  assert.equal((await post(handle, { ...env, CHAT_LIMITER: chatLimiter }, {})).status, 401);
  assert.equal(chatLimiter.keys.size, 0);

  const broken = { limit: async () => { throw new Error("binding down"); } };
  assert.equal((await post(handle, { ...env, CHAT_LIMITER: broken }, { token: await makeToken() })).status, 200);
  assert.equal((await post(handle, env, { token: await makeToken() })).status, 200);
});

test("made-up key IDs don't make the relay download Google's keys on every request", async () => {
  let fetches = 0;
  let time = NOW;
  const fetchImpl = async () => {
    fetches++;
    return new Response(JSON.stringify({ keys: [publicJwk] }), { headers: { "cache-control": "public, max-age=3600" } });
  };
  const getKey = createKeyStore(fetchImpl, () => time);
  assert.ok(await getKey("k1"));
  for (let i = 0; i < 20; i++) assert.equal(await getKey(`made-up-${i}`), null);
  assert.equal(fetches, 1);
  // After the pause an unknown key is looked up once more (Google may have rotated its keys).
  time += UNKNOWN_KEY_REFETCH_MS;
  assert.equal(await getKey("made-up-again"), null);
  assert.equal(await getKey("made-up-once-more"), null);
  assert.equal(fetches, 2);
  // Known keys never cause a download while the cache is fresh.
  assert.ok(await getKey("k1"));
  assert.equal(fetches, 2);
});
