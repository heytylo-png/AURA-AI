// Run with: node --test relay/test/
import { test } from "node:test";
import assert from "node:assert/strict";
import { createHandler, createKeyStore, validateChatBody, RelayError, PROPOSAL_TOOLS, MEMORY_CATEGORIES, UNKNOWN_KEY_REFETCH_MS, withinDailyLimit, webSearchTool, webFetchTool, MAX_IMAGE_BASE64_CHARS, MAX_IMAGES, MAX_TOTAL_IMAGE_BASE64_CHARS } from "../src/index.js";

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

function setup({ upstream, speechUpstream, openaiUpstream } = {}) {
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
    if (String(url).startsWith("https://api.openai.com/") && openaiUpstream) return openaiUpstream(String(url), init);
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
  assert.deepEqual(body, { ok: true, speech: false, media: false });
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
    { system: undefined, messages: [{ role: "user", content: "a" }, { role: "assistant", content: "b" }, { role: "user", content: "c" }], actions: false, web: false, fetch: false, media: false }
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

function fakeStore() {
  const data = new Map();
  return {
    data,
    puts: [],
    async get(key) { return data.has(key) ? data.get(key) : null; },
    async put(key, value, options) { data.set(key, value); this.puts.push({ key, value, options }); },
  };
}

test("daily limit: counts per user, per kind and per UTC day", async () => {
  const store = fakeStore();
  const day1 = Date.UTC(2026, 10, 5, 23, 59, 0);
  assert.equal(await withinDailyLimit(store, "chat", "u1", 2, day1), true);
  assert.equal(await withinDailyLimit(store, "chat", "u1", 2, day1), true);
  assert.equal(await withinDailyLimit(store, "chat", "u1", 2, day1), false);
  // Another user, another kind and the next day each have their own count.
  assert.equal(await withinDailyLimit(store, "chat", "u2", 2, day1), true);
  assert.equal(await withinDailyLimit(store, "speech", "u1", 2, day1), true);
  assert.equal(await withinDailyLimit(store, "chat", "u1", 2, day1 + 2 * 60 * 1000), true);
  assert.equal(store.data.get("chat:u1:2026-11-05"), "2");
  assert.equal(store.data.get("chat:u1:2026-11-06"), "1");
  assert.ok(store.puts.every((p) => p.options.expirationTtl === 2 * 86400));
});

test("daily limit: allowed when there is no store or the store fails", async () => {
  assert.equal(await withinDailyLimit(undefined, "chat", "u1", 1, NOW), true);
  const broken = { get: async () => { throw new Error("down"); }, put: async () => {} };
  assert.equal(await withinDailyLimit(broken, "chat", "u1", 1, NOW), true);
});

test("over the daily limit: 429 daily_limit and nothing reaches Claude or ElevenLabs", async () => {
  const { handle, env, calls } = setup();
  env.USAGE = fakeStore();
  env.DAILY_CHAT_LIMIT = "1";
  env.DAILY_SPEAK_LIMIT = "1";
  const token = await makeToken();
  const chat = () => handle(new Request("https://relay.example/v1/chat", {
    method: "POST", headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ messages: [{ role: "user", content: "hi" }] }),
  }), env);
  const speak = () => handle(new Request("https://relay.example/v1/speak", {
    method: "POST", headers: { authorization: `Bearer ${token}` }, body: JSON.stringify({ text: "hi" }),
  }), { ...env, ELEVENLABS_API_KEY: "el-key", ELEVENLABS_VOICE_ID: "voice" });

  assert.equal((await chat()).status, 200);
  const blocked = await chat();
  assert.equal(blocked.status, 429);
  assert.equal((await blocked.json()).error.type, "daily_limit");
  assert.equal(calls.filter((c) => c.url.includes("anthropic.com")).length, 1);

  assert.equal((await speak()).status, 200);
  assert.equal((await speak()).status, 429);
  assert.equal(calls.filter((c) => c.url.includes("elevenlabs.io")).length, 1);
});

test("a stranger's requests don't use up anyone's daily count", async () => {
  const { handle, env } = setup();
  env.USAGE = fakeStore();
  const token = await makeToken({ sub: "stranger" });
  const res = await handle(new Request("https://relay.example/v1/chat", {
    method: "POST", headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ messages: [{ role: "user", content: "hi" }] }),
  }), env);
  assert.equal(res.status, 403);
  assert.equal(env.USAGE.data.size, 0);
});

async function chatWith(body, envExtra = {}) {
  let sent;
  const { handle, env } = setup({
    upstream: (init) => {
      sent = JSON.parse(init.body);
      return new Response("event: message_stop\ndata: {}\n\n", { headers: { "content-type": "text/event-stream" } });
    },
  });
  Object.assign(env, envExtra);
  const token = await makeToken();
  const res = await handle(new Request("https://relay.example/v1/chat", {
    method: "POST", headers: { authorization: `Bearer ${token}` },
    body: JSON.stringify({ messages: [{ role: "user", content: "hi" }], ...body }),
  }), env);
  return { res, sent, env };
}

test("web search is added only when the app asks, with the relay's own cap", async () => {
  const off = await chatWith({});
  assert.equal(off.res.status, 200);
  assert.equal(off.sent.tools, undefined);

  const on = await chatWith({ web: true });
  assert.equal(on.res.status, 200);
  assert.deepEqual(on.sent.tools, [{ type: "web_search_20260209", name: "web_search", max_uses: 3 }]);
  assert.equal(on.sent.tool_choice, undefined);

  // Together with the proposal tools: both are there, and no tool_choice. The Claude API answers
  // 400 "disable_parallel_tool_use: true cannot be used with programmatic tool calling" otherwise
  // (seen on the phone); the app keeps only the first proposal in a reply.
  const both = await chatWith({ web: true, actions: true });
  assert.deepEqual(both.sent.tools.map((t) => t.name), [...PROPOSAL_TOOLS.map((t) => t.name), "web_search"]);
  assert.equal(both.sent.tool_choice, undefined);

  // Whatever else the app sends is ignored.
  const sneaky = await chatWith({ web: true, tools: [{ type: "web_fetch_20260209", name: "web_fetch" }], max_uses: 50 });
  assert.deepEqual(sneaky.sent.tools, [{ type: "web_search_20260209", name: "web_search", max_uses: 3 }]);

  const bad = await chatWith({ web: "yes" });
  assert.equal(bad.res.status, 400);
});

test("link opening is added only when the app asks, with the relay's own caps", async () => {
  const fetchTool = { type: "web_fetch_20260209", name: "web_fetch", max_uses: 3, max_content_tokens: 20000 };
  const on = await chatWith({ fetch: true });
  assert.equal(on.res.status, 200);
  assert.deepEqual(on.sent.tools, [fetchTool]);

  // With proposals: no tool_choice, for the same reason as web search.
  const withActions = await chatWith({ fetch: true, actions: true });
  assert.deepEqual(withActions.sent.tools.map((t) => t.name), [...PROPOSAL_TOOLS.map((t) => t.name), "web_fetch"]);
  assert.equal(withActions.sent.tool_choice, undefined);

  const all = await chatWith({ fetch: true, web: true });
  assert.deepEqual(all.sent.tools.map((t) => t.name), ["web_search", "web_fetch"]);

  assert.equal((await chatWith({ fetch: "yes" })).res.status, 400);
  assert.equal(webFetchTool({ WEB_FETCH_MAX_USES: "500" }).max_uses, 10);
  assert.equal(webFetchTool({ WEB_FETCH_MAX_USES: "x" }).max_uses, 3);
});

test("searches per reply come from the relay setting, within bounds", () => {
  assert.equal(webSearchTool({}).max_uses, 3);
  assert.equal(webSearchTool({ WEB_SEARCH_MAX_USES: "5" }).max_uses, 5);
  assert.equal(webSearchTool({ WEB_SEARCH_MAX_USES: "500" }).max_uses, 10);
  assert.equal(webSearchTool({ WEB_SEARCH_MAX_USES: "0" }).max_uses, 3);
  assert.equal(webSearchTool({ WEB_SEARCH_MAX_USES: "abc" }).max_uses, 3);
});

test("replies with web search have their own daily cap; plain chat is unaffected", async () => {
  const store = fakeStore();
  const env = { USAGE: store, DAILY_SEARCH_LIMIT: "1" };
  assert.equal((await chatWith({ web: true }, env)).res.status, 200);
  const blocked = await chatWith({ web: true }, env);
  assert.equal(blocked.res.status, 429);
  const err = (await blocked.res.json()).error;
  assert.equal(err.type, "daily_limit");
  assert.match(err.message, /web search/);
  assert.equal(blocked.sent, undefined, "nothing reached Claude");
  // Without web search the same user can still chat.
  assert.equal((await chatWith({}, env)).res.status, 200);
});

const JPEG = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49]).toString("base64");
const image = (extra = {}) => ({ type: "image", source: { type: "base64", media_type: "image/jpeg", data: JPEG, ...extra } });

test("an image can come with the newest message, rebuilt from checked fields only", async () => {
  const ok = validateChatBody({
    messages: [
      { role: "user", content: "earlier" },
      { role: "assistant", content: "reply" },
      { role: "user", content: [{ ...image(), cache_control: { type: "ephemeral" }, extra: 1 }, { type: "text", text: "What is this?", citations: [] }] },
    ],
  });
  assert.deepEqual(ok.messages[2].content, [
    { type: "image", source: { type: "base64", media_type: "image/jpeg", data: JPEG } },
    { type: "text", text: "What is this?" },
  ]);
  // An image on its own is fine.
  assert.deepEqual(validateChatBody({ messages: [{ role: "user", content: [image()] }] }).messages[0].content, [image()]);
  assert.deepEqual(validateChatBody({ messages: [{ role: "user", content: [image(), { type: "text", text: "  " }] }] }).messages[0].content, [image()]);
});

test("images are refused anywhere else, in other forms, or too large", () => {
  const refused = (messages, status = 400) => {
    try {
      validateChatBody({ messages });
      assert.fail("expected a refusal");
    } catch (e) {
      assert.ok(e instanceof RelayError, String(e));
      assert.equal(e.status, status);
    }
  };
  // Not in an earlier message, and not from the assistant.
  refused([{ role: "user", content: [image()] }, { role: "assistant", content: "x" }, { role: "user", content: "y" }]);
  // Images first, at most MAX_IMAGES, at most one text block, no other block types, no URLs or files.
  refused([{ role: "user", content: Array.from({ length: MAX_IMAGES + 1 }, () => image()) }]);
  refused([{ role: "user", content: [{ type: "text", text: "a" }, image()] }]);
  refused([{ role: "user", content: [image(), { type: "text", text: "a" }, image()] }]);
  refused([{ role: "user", content: [image(), { type: "text", text: " " }, { type: "text", text: "a" }] }]);
  refused([{ role: "user", content: [{ type: "text", text: "plain text needs no blocks" }] }]);
  refused([{ role: "user", content: [{ type: "document", source: { type: "base64", media_type: "application/pdf", data: JPEG } }] }]);
  refused([{ role: "user", content: [{ type: "image", source: { type: "url", url: "https://example.com/a.jpg" } }] }]);
  refused([{ role: "user", content: [{ type: "image", source: { type: "file", file_id: "file_1" } }] }]);
  refused([{ role: "user", content: [image({ media_type: "image/svg+xml" })] }]);
  refused([{ role: "user", content: [image({ data: "not base64!" })] }]);
  refused([{ role: "user", content: [image({ data: "" })] }]);
  refused([{ role: "user", content: [image({ data: "A".repeat(MAX_IMAGE_BASE64_CHARS + 4) })] }], 413);
  // Each image within its own limit, but too much together.
  const big = "A".repeat(MAX_IMAGE_BASE64_CHARS);
  const tooMany = Math.floor(MAX_TOTAL_IMAGE_BASE64_CHARS / MAX_IMAGE_BASE64_CHARS) + 1;
  refused([{ role: "user", content: Array.from({ length: tooMany }, () => image({ data: big })) }], 413);
});

test("several images can come with the newest message, in order", () => {
  const second = "B".repeat(8);
  const content = validateChatBody({
    messages: [{ role: "user", content: [image(), image({ data: second }), { type: "text", text: "Which is better?" }] }]
  }).messages[0].content;
  assert.deepEqual(content.map((b) => b.type), ["image", "image", "text"]);
  assert.equal(content[1].source.data, second);
  assert.equal(validateChatBody({ messages: [{ role: "user", content: Array.from({ length: MAX_IMAGES }, () => image()) }] }).messages[0].content.length, MAX_IMAGES);
});

test("an image reaches Claude as one image block", async () => {
  const { res, sent } = await chatWith({ messages: [{ role: "user", content: [image(), { type: "text", text: "What is this?" }] }] });
  assert.equal(res.status, 200);
  assert.equal(sent.messages[0].content[0].type, "image");
  assert.equal(sent.messages[0].content[0].source.data, JPEG);
});

// ---- Images and videos (media.js) ----

async function media(path, { method = "POST", body, envExtra = { OPENAI_API_KEY: "sk-openai" }, openai, token } = {}) {
  const sent = [];
  const { handle, env } = setup({
    openaiUpstream: async (url, init) => {
      sent.push({ url, init });
      return openai ? openai(url, init) : new Response("{}", { status: 500 });
    },
  });
  Object.assign(env, envExtra);
  const auth = token ?? (await makeToken());
  const res = await handle(new Request(`https://relay.example${path}`, {
    method,
    headers: { authorization: `Bearer ${auth}`, "content-type": "application/json" },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  }), env);
  return { res, sent, env };
}

test("media: not configured without an OpenAI key, and sign-in is required", async () => {
  const off = await media("/v1/image", { body: { prompt: "a cat" }, envExtra: {} });
  assert.equal(off.res.status, 503);
  assert.equal((await off.res.json()).error.type, "media_not_configured");
  const placeholder = await media("/v1/image", { body: { prompt: "a cat" }, envExtra: { OPENAI_API_KEY: "not-set" } });
  assert.equal(placeholder.res.status, 503);
  const noToken = await media("/v1/image", { body: { prompt: "a cat" }, token: "bad" });
  assert.equal(noToken.res.status, 401);
  assert.equal(noToken.sent.length, 0);
});

test("media: an image is made with the relay's model, quality and size, never the app's", async () => {
  const done = await media("/v1/image", {
    body: { prompt: "  a red bicycle  ", shape: "landscape", model: "dall-e-3", quality: "max", n: 10 },
    openai: () => new Response(JSON.stringify({ data: [{ b64_json: "AAAA" }] })),
  });
  assert.equal(done.res.status, 200);
  assert.deepEqual(await done.res.json(), { image: "AAAA", mediaType: "image/jpeg" });
  assert.equal(done.sent[0].url, "https://api.openai.com/v1/images/generations");
  assert.equal(done.sent[0].init.headers.authorization, "Bearer sk-openai");
  const sent = JSON.parse(done.sent[0].init.body);
  assert.equal(sent.model, "gpt-image-2");
  assert.equal(sent.quality, "medium");
  assert.equal(sent.size, "1536x1024");
  assert.equal(sent.n, 1);
  assert.equal(sent.prompt, "a red bicycle");
  assert.equal(sent.output_format, "jpeg");
  // A hash stands in for the user, never their Firebase ID.
  assert.ok(sent.safety_identifier && !sent.safety_identifier.includes(ALLOWED));

  const configured = await media("/v1/image", {
    body: { prompt: "x" }, envExtra: { OPENAI_API_KEY: "k", OPENAI_IMAGE_MODEL: "gpt-image-2.5-flare", OPENAI_IMAGE_QUALITY: "ultra" },
    openai: () => new Response(JSON.stringify({ data: [{ b64_json: "AAAA" }] })),
  });
  const c = JSON.parse(configured.sent[0].init.body);
  assert.equal(c.model, "gpt-image-2.5-flare");
  assert.equal(c.quality, "medium", "an unknown quality falls back to medium");
  assert.equal(c.size, "1024x1024");
});

test("media: bad image requests are refused before OpenAI is called; OpenAI's errors are passed on", async () => {
  for (const body of [{}, { prompt: "  " }, { prompt: 5 }, { prompt: "x".repeat(4001) }, { prompt: "x", shape: "round" }]) {
    const r = await media("/v1/image", { body });
    assert.ok(r.res.status === 400 || r.res.status === 413, JSON.stringify(body));
    assert.equal(r.sent.length, 0);
  }
  const blocked = await media("/v1/image", {
    body: { prompt: "x" },
    openai: () => new Response(JSON.stringify({ error: { message: "Your request was rejected by the safety system." } }), { status: 400 }),
  });
  assert.equal(blocked.res.status, 400);
  assert.match((await blocked.res.json()).error.message, /safety system/);
  const badKey = await media("/v1/image", { body: { prompt: "x" }, openai: () => new Response("{}", { status: 401 }) });
  assert.equal(badKey.res.status, 502, "a bad OpenAI key is the relay's problem");
});

test("media: images have their own daily cap", async () => {
  const store = fakeStore();
  const ok = () => new Response(JSON.stringify({ data: [{ b64_json: "AAAA" }] }));
  const envExtra = { OPENAI_API_KEY: "k", USAGE: store, DAILY_IMAGE_LIMIT: "1" };
  assert.equal((await media("/v1/image", { body: { prompt: "x" }, envExtra, openai: ok })).res.status, 200);
  const second = await media("/v1/image", { body: { prompt: "x" }, envExtra, openai: ok });
  assert.equal(second.res.status, 429);
  assert.equal(second.sent.length, 0);
});

test("media: a video is started within the relay's limits, and only its owner can check or download it", async () => {
  const started = await media("/v1/video", {
    body: { prompt: "waves at sunset", seconds: "8", shape: "landscape", model: "sora-2-pro" },
    openai: () => new Response(JSON.stringify({ id: "video_123", status: "queued", progress: 0 })),
  });
  assert.equal(started.res.status, 200);
  const job = await started.res.json();
  assert.equal(job.id, "video_123");
  assert.equal(job.status, "queued");
  assert.ok(job.ticket.length > 20);
  assert.equal(started.sent[0].url, "https://api.openai.com/v1/videos");
  const form = started.sent[0].init.body;
  assert.equal(form.get("model"), "sora-2");
  assert.equal(form.get("seconds"), "8");
  assert.equal(form.get("size"), "1280x720");
  assert.equal(form.get("prompt"), "waves at sunset");

  // Longer than the relay allows, or not a length OpenAI offers: refused before OpenAI.
  for (const seconds of ["12", "5", 8.5]) {
    const r = await media("/v1/video", { body: { prompt: "x", seconds } });
    assert.equal(r.res.status, 400, String(seconds));
    assert.equal(r.sent.length, 0);
  }
  const longAllowed = await media("/v1/video", {
    body: { prompt: "x", seconds: "12" }, envExtra: { OPENAI_API_KEY: "sk-openai", VIDEO_MAX_SECONDS: "12" },
    openai: () => new Response(JSON.stringify({ id: "video_9", status: "queued" })),
  });
  assert.equal(longAllowed.res.status, 200);

  const q = `id=video_123&ticket=${encodeURIComponent(job.ticket)}`;
  const status = await media(`/v1/video/status?${q}`, {
    method: "GET",
    openai: () => new Response(JSON.stringify({ id: "video_123", status: "in_progress", progress: 42 })),
  });
  assert.equal(status.res.status, 200);
  assert.deepEqual(await status.res.json(), { status: "in_progress", progress: 42 });
  assert.equal(status.sent[0].url, "https://api.openai.com/v1/videos/video_123");

  const failed = await media(`/v1/video/status?${q}`, {
    method: "GET",
    openai: () => new Response(JSON.stringify({ status: "failed", progress: 0, error: { code: "moderation_blocked", message: "Blocked." } })),
  });
  assert.deepEqual(await failed.res.json(), { status: "failed", progress: 0, error: "Blocked." });

  const content = await media(`/v1/video/content?${q}`, {
    method: "GET",
    openai: () => new Response(new Uint8Array([0, 0, 0, 24]), { headers: { "content-type": "application/binary" } }),
  });
  assert.equal(content.res.status, 200);
  assert.equal(content.res.headers.get("content-type"), "video/mp4");
  assert.equal(content.sent[0].url, "https://api.openai.com/v1/videos/video_123/content");
  assert.equal((await content.res.arrayBuffer()).byteLength, 4);

  // A wrong ticket, another video's ID, or a malformed ID: nothing reaches OpenAI.
  for (const query of [`id=video_123&ticket=nope`, `id=video_999&ticket=${encodeURIComponent(job.ticket)}`, `id=../x&ticket=a`, ""]) {
    const r = await media(`/v1/video/status?${query}`, { method: "GET" });
    assert.ok(r.res.status === 404 || r.res.status === 400, query);
    assert.equal(r.sent.length, 0);
  }
});

test("media: videos have their own daily cap; checking on one doesn't count", async () => {
  const store = fakeStore();
  const envExtra = { OPENAI_API_KEY: "k", USAGE: store, DAILY_VIDEO_LIMIT: "1" };
  const openai = () => new Response(JSON.stringify({ id: "video_1", status: "queued" }));
  assert.equal((await media("/v1/video", { body: { prompt: "x" }, envExtra, openai })).res.status, 200);
  assert.equal((await media("/v1/video", { body: { prompt: "x" }, envExtra, openai })).res.status, 429);
});

test("media: Claude gets the creation tools only when the app asks and the relay has a key", async () => {
  const off = await chatWith({ media: true });
  assert.equal(off.sent.tools, undefined, "no OpenAI key: no tools");
  const on = await chatWith({ media: true }, { OPENAI_API_KEY: "k" });
  assert.deepEqual(on.sent.tools.map((t) => t.name), ["generate_image", "generate_video"]);
  assert.deepEqual(on.sent.tool_choice, { type: "auto", disable_parallel_tool_use: true });
  const notAsked = await chatWith({}, { OPENAI_API_KEY: "k" });
  assert.equal(notAsked.sent.tools, undefined);
  const withWeb = await chatWith({ media: true, web: true }, { OPENAI_API_KEY: "k" });
  assert.equal(withWeb.sent.tool_choice, undefined);
  assert.equal((await chatWith({ media: "yes" }, { OPENAI_API_KEY: "k" })).res.status, 400);
});
