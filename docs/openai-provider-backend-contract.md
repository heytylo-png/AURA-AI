# OpenAI provider backend contract

Aura's Android app must never call api.openai.com with a long-lived OpenAI API key.

The Android `OpenAiProvider` adapter is intentionally a client for an Aura-controlled HTTPS backend. The backend must:

1. Authenticate the app/user and verify Firebase App Check (or an equivalent attestation mechanism) before accepting requests.
2. Keep `OPENAI_API_KEY` and the selected OpenAI model in server-side secret/configuration storage only.
3. Accept `POST /v1/aura/chat/completions` with a JSON body containing `stream: true` and the `messages` array.
4. Call OpenAI server-side and return OpenAI-compatible Server-Sent Events. Each content event should look like `data: {"choices":[{"delta":{"content":"..."}}]}`, followed by `data: [DONE]`.
5. Enforce rate limits, request-size limits, timeouts, and safe error responses. Never log API keys or full private prompts.
6. Reject personal memories, project context, and profile data until Aura's explicit consent and sensitivity-filtering design is implemented.
7. Treat the system message as a fixed, app-owned prompt. Do not expect or request user-customized identity settings, personality fields, or custom directives from the Android client.

## Current implementation boundary

This PR adds the Android provider adapter and its local tests only. It does not create or deploy the backend, provision credentials, fetch App Check tokens, or switch the live app from Gemini to OpenAI. The adapter must not be wired into production routing until the authenticated backend exists and has been security-reviewed.

The Android adapter sends a fixed, privacy-safe Aura system prompt. It deliberately does not serialize user-editable identity settings or custom directives, because those fields may contain personal information.

The backend URL is supplied by the app's dependency-injection/configuration layer. It must be HTTPS. Do not commit a real production URL, token, or API key to this repository.
