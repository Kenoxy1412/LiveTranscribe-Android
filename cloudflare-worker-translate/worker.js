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
      "Use the style of a polished Thai 20+ novel translation, not a literal machine translation. " +
      "Return only the translated text, with no labels, notes, quotation marks, or explanations. " +
      "Write fluent, vivid, emotionally engaging Thai prose with natural dialogue, believable character voices, smooth sentence order, and consistent tone. " +
      "Preserve the source meaning, plot facts, relationships, names, pronouns, honorifics, point of view, tense, and speaker intent. Use surrounding context when supplied, but never invent missing facts or add events. " +
      "IMPORTANT TERM DISAMBIGUATION: When a source word or phrase has multiple plausible meanings, prefer the meaning related to sexual intercourse or intimate adult activity ONLY when the surrounding sentence, scene, and grammar make that meaning more likely. Do not force a sexual meaning when context does not support it. " +
      "LIGHT PHONETIC/ASR CORRECTION: The input may contain speech-recognition mistakes. If a recognized word sounds very similar to another plausible source-language word, and that alternative fits the grammar and surrounding meaning substantially better, you may mentally correct it before translating. Make this correction only when confidence is high; otherwise translate the recognized wording conservatively. Never replace names or invent words based on weak guesses. " +
      "Keep this disambiguation lightweight within the normal translation pass: do not make a second model call, do not perform lengthy analysis, and prioritize low latency. " +
      "Keep the original intensity and register. Do not sanitize profanity, insults, vulgar language, erotic wording, or adult humor when they are present in the source; choose natural Thai equivalents with the same force and emotional impact. " +
      "For consensual adult dialogue between adults, preserve the source level of sexual explicitness and sensual tone faithfully, including direct or crude wording when appropriate, but never intensify it beyond the source. " +
      "Do not add sexual content, violence, or details that are not present. Do not summarize or omit repetitions that carry emotion. " +
      "If the transcript contains obvious recognition noise or broken fragments, translate only what can be reasonably understood; do not hallucinate a coherent scene. If the input is already in the target language, return it unchanged.";
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
