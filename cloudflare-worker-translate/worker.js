/**
 * LiveCaptionN OpenAI translation Worker.
 * Deploy this file as the entry point for livecaptionn-translate.
 * Required secret: OPENAI_API_KEY
 */
const MODEL = "gpt-6-luna";
const MAX_TEXT_LENGTH = 1200;
const MAX_OUTPUT_TOKENS = 220;

export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type",
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store"
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") {
      return new Response(JSON.stringify({ ok: true, service: "LiveCaptionN translation" }), { headers: cors });
    }
    if (url.pathname !== "/translate" || request.method !== "POST") {
      return new Response(JSON.stringify({ error: "Not found" }), { status: 404, headers: cors });
    }
    if (!env.OPENAI_API_KEY) {
      return new Response(JSON.stringify({ error: "Worker secret OPENAI_API_KEY is not configured" }), { status: 503, headers: cors });
    }
    let body;
    try { body = await request.json(); } catch {
      return new Response(JSON.stringify({ error: "Invalid JSON" }), { status: 400, headers: cors });
    }
    const text = typeof body.text === "string" ? body.text.trim() : "";
    const source = typeof body.source === "string" ? body.source : "auto";
    const target = typeof body.target === "string" ? body.target : "th";
    if (!text || text.length > MAX_TEXT_LENGTH) {
      return new Response(JSON.stringify({ error: "Text must contain 1 to 1200 characters" }), { status: 400, headers: cors });
    }
    if (!/^[a-zA-Z-]{2,12}$/.test(source) || !/^[a-zA-Z-]{2,12}$/.test(target)) {
      return new Response(JSON.stringify({ error: "Invalid language code" }), { status: 400, headers: cors });
    }
    const prompt = "Translate the user's speech transcript from " + source + " into " + target + ". " +
      "Return only the translation, with no quotes, labels, explanations, or alternatives. " +
      "Preserve the exact meaning, intent, names, numbers, speaker tone, and context. Do not summarize, omit, soften, censor, or add details. " +
      "For Thai output, use fluent, natural spoken Thai suitable for subtitles and dubbing, not stiff word-for-word Thai. " +
      "Keep the original register: if the source is casual, use casual Thai; if it is formal, use formal Thai. " +
      "For consensual adult-oriented dialogue between adults, translate the original wording and level of explicitness faithfully using natural Thai adult vocabulary; do not make it more graphic than the source. " +
      "Never infer or introduce sexual content that is not present in the source. If the input is already in the target language, return it unchanged.";
    const upstream = await fetch("https://api.openai.com/v1/responses", {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + env.OPENAI_API_KEY,
        "Content-Type": "application/json"
      },
      body: JSON.stringify({
        model: MODEL,
        reasoning: { effort: "none" },
        max_output_tokens: MAX_OUTPUT_TOKENS,
        store: false,
        input: [
          { role: "system", content: [{ type: "input_text", text: prompt }] },
          { role: "user", content: [{ type: "input_text", text }] }
        ]
      })
    });
    const result = await upstream.json().catch(() => ({}));
    if (!upstream.ok) {
      return new Response(JSON.stringify({ error: result.error?.message || "OpenAI request failed", status: upstream.status }), {
        status: upstream.status === 429 ? 429 : 502, headers: cors
      });
    }
    const translated = (result.output || [])
      .flatMap(item => item.content || [])
      .filter(item => item.type === "output_text")
      .map(item => item.text || "")
      .join("").trim();
    if (!translated) return new Response(JSON.stringify({ error: "Empty translation" }), { status: 502, headers: cors });
    return new Response(JSON.stringify({ translatedText: translated }), { headers: cors });
  }
};
