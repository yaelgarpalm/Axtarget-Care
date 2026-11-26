import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createMedicationLabelHandler, MAX_BODY_BYTES } from "./handler.ts";

const userId = "aa000000-0000-0000-0000-000000000001";
const env = { supabaseUrl: "https://project.invalid", anonKey: "public-test", serviceKey: "server-test", nvidiaKey: "provider-test", model: "fixed-model" };
const image = readFileSync(new URL("./tiny-test.jpg", import.meta.url)).toString("base64");
const provider = { choices: [{ message: { content: '```json\n{"name":"Tempra","dose":"50 mg"}\n```' } }] };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

function fixture(options: { auth?: () => Response; quota?: () => Response; ocr?: (init: RequestInit) => Response; provider?: (init: RequestInit) => Promise<Response> | Response; env?: typeof env; timeout?: number } = {}) {
  const calls: { url: string; init: RequestInit }[] = [];
  const handler = createMedicationLabelHandler({ env: options.env ?? env, providerTimeoutMs: options.timeout,
    fetch: (async (url, init = {}) => {
      calls.push({ url: String(url), init });
      if (String(url).endsWith("/auth/v1/user")) return options.auth?.() ?? json({ id: userId });
      if (String(url).endsWith("/rpc/reserve_medication_scan")) return options.quota?.() ?? json(true);
      if (String(url) === "https://ai.api.nvidia.com/v1/retrieval/nvidia/nemotron-3.5-parse/parse")
        return options.ocr?.(init) ?? json({}, 503);
      assert.equal(String(url), "https://integrate.api.nvidia.com/v1/chat/completions");
      return options.provider ? options.provider(init) : json(provider);
    }) as typeof fetch,
  });
  return { handler, calls };
}
function request(body: unknown = { image_base64: image }, headers: Record<string, string> = {}) {
  return new Request("https://function.invalid", { method: "POST", headers: { Authorization: "Bearer user-test", "Content-Type": "application/json", ...headers }, body: JSON.stringify(body) });
}

test("signed-in scan uses only server-controlled request and returns bounded fields", async () => {
  const { handler, calls } = fixture();
  const response = await handler(request());
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), { result: { name: "Tempra", dose: "50 mg", presentation: "", frequency: "", anchor_time: "" } });
  assert.equal(calls.length, 3);
  assert.equal((calls[0].init.headers as any).Authorization, "Bearer user-test");
  assert.deepEqual(JSON.parse(calls[1].init.body as string), { p_user_id: userId });
  assert.equal((calls[1].init.headers as any).Authorization, "Bearer server-test");
  const body = JSON.parse(calls[2].init.body as string);
  assert.equal(body.model, "fixed-model"); assert.equal(body.max_tokens, 1200); assert.equal(body.stream, false);
  assert.equal(body.messages[0].content[1].image_url.url, "data:image/jpeg;base64," + image);
  assert.equal((calls[2].init.headers as any).Authorization, "Bearer provider-test");
  calls.forEach(call => assert.equal(call.init.redirect, "error"));
});

test("method and missing/malformed authorization are rejected before any fetch", async () => {
  const { handler, calls } = fixture();
  assert.equal((await handler(new Request("https://function.invalid"))).status, 405);
  for (const Authorization of ["", "Bearer ", "Basic abc", "Bearer a b"]) assert.equal((await handler(request(undefined, { Authorization }))).status, 401);
  assert.equal(calls.length, 0);
});

test("forged and expired tokens require authoritative user verification", async () => {
  for (const status of [401, 403]) {
    const { handler, calls } = fixture({ auth: () => json({ error: "forged" }, status) });
    assert.equal((await handler(request())).status, 401); assert.equal(calls.length, 1);
  }
});

test("anonymous or malformed users cannot consume quota or call provider", async () => {
  for (const user of [{ id: userId, is_anonymous: true }, { id: "unverified" }, {}]) {
    const { handler, calls } = fixture({ auth: () => json(user) });
    assert.equal((await handler(request())).status, 401); assert.equal(calls.length, 1);
  }
});

test("upstream-control fields, URLs, arrays and invalid base64/JPEG rejected", async () => {
  for (const body of [null, [], {}, { image_base64: image, model: "attacker" }, { image_base64: image, prompt: "override" },
      { image_base64: "https://attacker.invalid" }, { image_base64: "@@==" }, { image_base64: "/9j/2Q==" }, { image_base64: "dGVzdA==" }, { image_base64: 12 }]) {
    const { handler, calls } = fixture();
    assert.equal((await handler(request(body))).status, 400); assert.equal(calls.length, 1);
  }
});

test("content type and declared size enforced before buffering body", async () => {
  for (const headers of [{ "Content-Type": "text/plain" }, { "Content-Length": String(MAX_BODY_BYTES + 1) }]) {
    const { handler, calls } = fixture();
    assert.equal((await handler(request(undefined, headers))).status, headers["Content-Type"] ? 415 : 413);
    assert.equal(calls.length, 1);
  }
});

test("streaming input exceeding limit is cancelled even without Content-Length", async () => {
  const { handler, calls } = fixture();
  let cancelled = false;
  const stream = new ReadableStream({ pull(controller) { controller.enqueue(new Uint8Array(64 * 1024)); }, cancel() { cancelled = true; } });
  const req = new Request("https://function.invalid", { method: "POST", headers: { Authorization: "Bearer test", "Content-Type": "application/json" }, body: stream, duplex: "half" } as RequestInit);
  assert.equal((await handler(req)).status, 413); assert.equal(cancelled, true); assert.equal(calls.length, 1);
});

test("invalid JSON does not invoke quota or provider", async () => {
  const { handler, calls } = fixture();
  const req = new Request("https://function.invalid", { method: "POST", headers: { Authorization: "Bearer test", "Content-Type": "application/json" }, body: "{" });
  assert.equal((await handler(req)).status, 400); assert.equal(calls.length, 1);
});

test("persistent quota denial prevents paid invocation", async () => {
  const { handler, calls } = fixture({ quota: () => json(false) });
  assert.equal((await handler(request())).status, 429); assert.equal(calls.length, 2);
});

test("quota outages or malformed acknowledgement fail closed", async () => {
  for (const quota of [() => json({}, 500), () => json({ allowed: true })]) {
    const { handler, calls } = fixture({ quota });
    assert.equal((await handler(request())).status, 503); assert.equal(calls.length, 2);
  }
});

test("provider redirects and failures are not followed or echoed", async () => {
  for (const status of [302, 401, 429, 500]) {
    const { handler, calls } = fixture({ provider: () => json({ secret: env.nvidiaKey }, status) });
    const response = await handler(request());
    assert.equal(response.status, 502); assert.equal(calls.length, 3);
    assert.equal((await response.text()).includes(env.nvidiaKey), false);
  }
});

test("provider response byte limit and field limits reject unbounded output", async () => {
  for (const content of ["x".repeat(33 * 1024), JSON.stringify({ name: "x".repeat(257) }), JSON.stringify({ name: {} }), "no JSON"]) {
    const { handler } = fixture({ provider: () => json({ choices: [{ message: { content } }] }) });
    assert.equal((await handler(request())).status, 502);
  }
});

test("provider timeout aborts without exposing credentials", async () => {
  const { handler } = fixture({ timeout: 10, provider: init => new Promise((_, reject) => {
    init.signal!.addEventListener("abort", () => reject(new Error(env.nvidiaKey)), { once: true });
  }) });
  // Keep the Node event loop alive while its unref'd AbortSignal timer fires.
  const keepAlive = setInterval(() => {}, 100);
  try { const response = await handler(request()); assert.equal(response.status, 503); assert.equal((await response.text()).includes(env.nvidiaKey), false); }
  finally { clearInterval(keepAlive); }
});

test("missing server secret disables only cloud without calling upstream", async () => {
  const { handler, calls } = fixture({ env: { ...env, nvidiaKey: "" } });
  assert.equal((await handler(request())).status, 503); assert.equal(calls.length, 0);
});

test("provider extra fields never reach the medication form", async () => {
  const { handler } = fixture({ provider: () => json({ choices: [{ message: { content: '{"name":"Tempra","secret":"discard","url":"https://attacker.invalid"}' } }] }) });
  const response = await handler(request()); const body = await response.json();
  assert.equal(response.status, 200); assert.deepEqual(Object.keys(body.result).sort(), ["anchor_time", "dose", "frequency", "name", "presentation"]);
});

const scanOutput = (medications: unknown[], notes = "", finish_reason = "stop") =>
  json({ choices: [{ finish_reason, message: { content: JSON.stringify({ medications, notes }) } }] });

test("prescriptions use document OCR and return each medicine plus separate notes", async () => {
  const { handler, calls } = fixture({
    ocr: init => {
      assert.equal((init.headers as Record<string, string>).Authorization, "Bearer provider-test");
      const form = init.body as FormData;
      const imagePart = form.get("document") as File;
      assert.equal(imagePart.type, "image/jpeg");
      assert.equal(imagePart.name, "prescription.jpg");
      return json({ content: "<x_0.10><y_0.20>Dos líneas de la receta<class_Text>\n<x_0.10>Notas de seguimiento" });
    },
    provider: init => {
      const body = JSON.parse(init.body as string);
      assert.equal(body.model, env.model);
      assert.ok(body.max_tokens > 1200);
      assert.match(body.messages[0].content[0].text, /Dos líneas de la receta/);
      assert.doesNotMatch(body.messages[0].content[0].text, /class_Text/);
      return scanOutput([
        { name: "Medicamento A", dose: "1 tableta", presentation: "500 mg", instructions: "Instrucción A" },
        { name: "Medicamento B", dose: "2 gotas", instructions: "Instrucción B", privateField: "discard" },
      ], "Nota general");
    },
  });
  const response = await handler(request({ image_base64: image, document: "prescription" }));
  assert.equal(response.status, 200);
  const { scan } = await response.json();
  assert.deepEqual(scan.medications.map((entry: Record<string, string>) =>
    [entry.name, entry.dose, entry.instructions]), [
    ["Medicamento A", "1 tableta", "Instrucción A"],
    ["Medicamento B", "2 gotas", "Instrucción B"],
  ]);
  assert.equal(scan.notes, "Nota general");
  assert.equal("privateField" in scan.medications[1], false);
  assert.equal(calls.length, 4);
  assert.ok(calls.every(call => call.init.redirect === "error"));
});

test("box and bottle scans skip document OCR and strip directions and general notes", async () => {
  const { handler, calls } = fixture({
    provider: () => scanOutput([{ name: "Medicamento A", dose: "500 mg + 125 mg", frequency: "Diaria", anchor_time: "08:00", instructions: "Publicidad" }], "Datos fuera del envase"),
  });
  const response = await handler(request({ image_base64: image, document: "label" }));
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    scan: { medications: [
      { name: "Medicamento A", dose: "500 mg + 125 mg", presentation: "", frequency: "", anchor_time: "", instructions: "" },
    ], notes: "" },
  });
  assert.equal(calls.length, 3);
});

test("prescription vision remains available when the auxiliary OCR fails", async () => {
  const { handler, calls } = fixture({ provider: () => scanOutput([{ name: "Medicamento A" }, { name: "Medicamento B" }]) });
  const response = await handler(request({ image_base64: image, document: "prescription" }));
  assert.equal(response.status, 200);
  assert.equal((await response.json()).scan.medications.length, 2);
  assert.equal(calls.length, 4);
});

test("rejects unknown document kinds and partial or oversized extraction results", async () => {
  const invalid = fixture();
  assert.equal((await invalid.handler(request({ image_base64: image, document: "other" }))).status, 400);
  assert.equal(invalid.calls.length, 1);
  for (const response of [
    () => scanOutput([{ name: "Med A" }, { name: "Med B" }]),
    () => scanOutput([{ name: "Med A" }], "", "length"),
    () => scanOutput([{ name: "Med A", dose: "x".repeat(257) }]),
  ]) {
    const { handler } = fixture({ provider: response });
    assert.equal((await handler(request({ image_base64: image, document: "label" }))).status, 502);
  }
});
