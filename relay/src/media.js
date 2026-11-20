// Image and video creation with OpenAI, for the Aura relay. The OpenAI key stays here, like the
// Anthropic key; the app only ever talks to the relay.
//
// POST /v1/image                 body: { "prompt": "...", "shape": "square" | "landscape" | "portrait" }
//                                response: { "image": "<base64 JPEG>", "mediaType": "image/jpeg" }
// POST /v1/video                 body: { "prompt": "...", "seconds": "4" | "8" | "12", "shape": "portrait" | "landscape" }
//                                response: { "id": "...", "ticket": "...", "status": "...", "progress": 0 }
// GET  /v1/video/status?id=&ticket=   response: { "status": "...", "progress": 0-100, "error": "..." }
// GET  /v1/video/content?id=&ticket=  response: the MP4
//
// The model, quality, size and length are fixed or bounded here, so a modified app can't order an
// expensive model or a long video. A video's ticket ties its ID to the user who started it, so
// nobody else can read or download it.
//
// Environment (all optional except the key):
//   OPENAI_API_KEY       secret; without it these endpoints answer "not configured"
//   OPENAI_IMAGE_MODEL   var, default gpt-image-2
//   OPENAI_IMAGE_QUALITY var, default medium (low, medium or high)
//   OPENAI_VIDEO_MODEL   var, default sora-2
//   VIDEO_MAX_SECONDS    var, default 8 (4, 8 or 12)
//   DAILY_IMAGE_LIMIT    var, default 20 images per user per day (needs USAGE)
//   DAILY_VIDEO_LIMIT    var, default 3 videos per user per day (needs USAGE)

export const OPENAI_URL = "https://api.openai.com/v1";
export const DEFAULT_IMAGE_MODEL = "gpt-image-2";
export const DEFAULT_IMAGE_QUALITY = "medium";
export const DEFAULT_VIDEO_MODEL = "sora-2";
export const DEFAULT_VIDEO_MAX_SECONDS = 8;
export const DEFAULT_DAILY_IMAGE_LIMIT = 20;
export const DEFAULT_DAILY_VIDEO_LIMIT = 3;
export const MAX_MEDIA_PROMPT_CHARS = 4000;
const IMAGE_QUALITIES = ["low", "medium", "high"];
export const IMAGE_SIZES = { square: "1024x1024", landscape: "1536x1024", portrait: "1024x1536" };
export const VIDEO_SIZES = { portrait: "720x1280", landscape: "1280x720" };
export const VIDEO_SECONDS = ["4", "8", "12"];
const VIDEO_ID = /^[A-Za-z0-9_-]{1,128}$/;

// Tools Claude may use to ask for an image or a video. Defined here, never taken from the app. The
// call only describes what to make: the app creates an image straight away and asks the user before
// creating a video, which costs much more.
export const MEDIA_TOOLS = [
  {
    name: "generate_image",
    description:
      "Create a picture for the user with an image model. Use only when the user asks you to make, draw, design or " +
      "generate an image. Write a detailed visual description in English. It is sent to OpenAI, so leave out the " +
      "user's personal details unless the picture is meant to show them. One image per reply.",
    input_schema: {
      type: "object",
      properties: {
        prompt: { type: "string", maxLength: MAX_MEDIA_PROMPT_CHARS, description: "What the image shows, its style, composition and mood." },
        shape: { type: "string", enum: Object.keys(IMAGE_SIZES), description: "The image's shape." },
      },
      required: ["prompt", "shape"],
      additionalProperties: false,
    },
  },
  {
    name: "generate_video",
    description:
      "Create a short video clip (with sound) for the user with a video model. Use only when the user asks for a video. " +
      "The user is asked to confirm first, because videos cost much more than images. Describe the shot in English: " +
      "subject, action, setting, camera and lighting. It is sent to OpenAI, so leave out personal details. One video per reply.",
    input_schema: {
      type: "object",
      properties: {
        prompt: { type: "string", maxLength: MAX_MEDIA_PROMPT_CHARS, description: "The shot: subject, action, setting, camera, lighting, sound." },
        seconds: { type: "string", enum: VIDEO_SECONDS, description: "Length in seconds; 4 unless the user wants longer." },
        shape: { type: "string", enum: Object.keys(VIDEO_SIZES), description: "Portrait for phones, landscape for wide scenes." },
      },
      required: ["prompt", "seconds", "shape"],
      additionalProperties: false,
    },
  },
];

export class MediaError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

export function mediaConfigured(env) {
  return Boolean(env.OPENAI_API_KEY && env.OPENAI_API_KEY !== "not-set");
}

function cleanPrompt(body) {
  const prompt = typeof body?.prompt === "string" ? body.prompt.trim() : "";
  if (!prompt) throw new MediaError(400, "invalid_request", "prompt must be a non-empty string.");
  if (prompt.length > MAX_MEDIA_PROMPT_CHARS) {
    throw new MediaError(413, "request_too_large", `prompt must be at most ${MAX_MEDIA_PROMPT_CHARS} characters.`);
  }
  return prompt;
}

/** The longest video this relay allows: VIDEO_MAX_SECONDS if it is 4, 8 or 12, else 8. */
export function videoMaxSeconds(env) {
  const configured = String(env.VIDEO_MAX_SECONDS ?? "");
  return VIDEO_SECONDS.includes(configured) ? Number(configured) : DEFAULT_VIDEO_MAX_SECONDS;
}

export function validateImageBody(body) {
  const prompt = cleanPrompt(body);
  const shape = body?.shape ?? "square";
  if (!(shape in IMAGE_SIZES)) throw new MediaError(400, "invalid_request", "shape must be square, landscape or portrait.");
  return { prompt, size: IMAGE_SIZES[shape] };
}

export function validateVideoBody(body, maxSeconds) {
  const prompt = cleanPrompt(body);
  const shape = body?.shape ?? "portrait";
  if (!(shape in VIDEO_SIZES)) throw new MediaError(400, "invalid_request", "shape must be portrait or landscape.");
  const seconds = String(body?.seconds ?? "4");
  if (!VIDEO_SECONDS.includes(seconds)) throw new MediaError(400, "invalid_request", "seconds must be 4, 8 or 12.");
  if (Number(seconds) > maxSeconds) {
    throw new MediaError(400, "too_long", `Videos can be at most ${maxSeconds} seconds long on this relay.`);
  }
  return { prompt, seconds, size: VIDEO_SIZES[shape] };
}

function base64url(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** A ticket for one user's video: an HMAC of user and video ID, keyed with the relay's OpenAI key. */
export async function videoTicket(secret, uid, id) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return base64url(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(`video:${uid}:${id}`)));
}

/** OpenAI asks for a stable, non-identifying user ID for abuse monitoring: a hash of the Firebase ID. */
export async function safetyIdentifier(uid) {
  return base64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(`aura:${uid}`)));
}

async function upstreamError(res, what) {
  let detail = `OpenAI returned HTTP ${res.status} for the ${what}.`;
  try {
    const e = await res.json();
    if (e?.error?.message) detail = `OpenAI: ${e.error.message}`;
  } catch {}
  // A bad or unpaid key is the relay's problem, not the user's.
  const status = res.status === 401 || res.status === 403 ? 502 : res.status;
  return new MediaError(status, "media_upstream_error", detail);
}

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
}

async function checkTicket(env, uid, url) {
  const id = url.searchParams.get("id") || "";
  const ticket = url.searchParams.get("ticket") || "";
  if (!VIDEO_ID.test(id)) throw new MediaError(400, "invalid_request", "id is missing or malformed.");
  const expected = await videoTicket(env.OPENAI_API_KEY, uid, id);
  if (ticket.length !== expected.length || ticket !== expected) throw new MediaError(404, "not_found", "No such video for this user.");
  return id;
}

/**
 * Handles the media endpoints for a signed-in, allowed user. [limit] checks the per-minute and daily
 * limits for a kind ("image" or "video") and throws when one is used up.
 */
export async function handleMedia({ request, url, env, uid, fetchImpl, limit }) {
  if (!mediaConfigured(env)) {
    throw new MediaError(503, "media_not_configured", "The relay has no OpenAI key yet, so it can't create images or videos.");
  }
  const auth = { authorization: `Bearer ${env.OPENAI_API_KEY}` };
  const path = url.pathname;

  if (path === "/v1/image" && request.method === "POST") {
    const { prompt, size } = validateImageBody(await readJson(request));
    await limit("image", Number(env.DAILY_IMAGE_LIMIT) || DEFAULT_DAILY_IMAGE_LIMIT);
    const quality = IMAGE_QUALITIES.includes(env.OPENAI_IMAGE_QUALITY) ? env.OPENAI_IMAGE_QUALITY : DEFAULT_IMAGE_QUALITY;
    const res = await fetchImpl(`${OPENAI_URL}/images/generations`, {
      method: "POST",
      headers: { ...auth, "content-type": "application/json" },
      body: JSON.stringify({
        model: env.OPENAI_IMAGE_MODEL || DEFAULT_IMAGE_MODEL,
        prompt,
        n: 1,
        size,
        quality,
        output_format: "jpeg",
        output_compression: 90,
        safety_identifier: await safetyIdentifier(uid),
      }),
    });
    if (!res.ok) throw await upstreamError(res, "image");
    const out = await res.json();
    const image = out?.data?.[0]?.b64_json;
    if (typeof image !== "string" || !image) throw new MediaError(502, "media_upstream_error", "OpenAI returned no image.");
    return jsonResponse(200, { image, mediaType: "image/jpeg" });
  }

  if (path === "/v1/video" && request.method === "POST") {
    const { prompt, seconds, size } = validateVideoBody(await readJson(request), videoMaxSeconds(env));
    await limit("video", Number(env.DAILY_VIDEO_LIMIT) || DEFAULT_DAILY_VIDEO_LIMIT);
    const form = new FormData();
    form.append("prompt", prompt);
    form.append("model", env.OPENAI_VIDEO_MODEL || DEFAULT_VIDEO_MODEL);
    form.append("seconds", seconds);
    form.append("size", size);
    const res = await fetchImpl(`${OPENAI_URL}/videos`, { method: "POST", headers: auth, body: form });
    if (!res.ok) throw await upstreamError(res, "video");
    const video = await res.json();
    if (typeof video?.id !== "string" || !VIDEO_ID.test(video.id)) throw new MediaError(502, "media_upstream_error", "OpenAI returned no video ID.");
    return jsonResponse(200, {
      id: video.id,
      ticket: await videoTicket(env.OPENAI_API_KEY, uid, video.id),
      status: video.status,
      progress: Number(video.progress) || 0,
    });
  }

  if (path === "/v1/video/status" && request.method === "GET") {
    const id = await checkTicket(env, uid, url);
    const res = await fetchImpl(`${OPENAI_URL}/videos/${encodeURIComponent(id)}`, { headers: auth });
    if (!res.ok) throw await upstreamError(res, "video status");
    const video = await res.json();
    return jsonResponse(200, {
      status: video?.status,
      progress: Number(video?.progress) || 0,
      ...(video?.error?.message ? { error: String(video.error.message).slice(0, 500) } : {}),
    });
  }

  if (path === "/v1/video/content" && request.method === "GET") {
    const id = await checkTicket(env, uid, url);
    const res = await fetchImpl(`${OPENAI_URL}/videos/${encodeURIComponent(id)}/content`, { headers: { ...auth, accept: "application/binary" } });
    if (!res.ok) throw await upstreamError(res, "video download");
    return new Response(res.body, { status: 200, headers: { "content-type": "video/mp4", "cache-control": "no-store" } });
  }

  throw new MediaError(405, "method_not_allowed", "Wrong method for this endpoint.");
}

async function readJson(request) {
  try {
    return await request.json();
  } catch {
    throw new MediaError(400, "invalid_request", "Body must be valid JSON.");
  }
}

export const MEDIA_PATHS = ["/v1/image", "/v1/video", "/v1/video/status", "/v1/video/content"];
