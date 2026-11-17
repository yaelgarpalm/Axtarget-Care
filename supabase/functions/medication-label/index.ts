import { createMedicationLabelHandler } from "./handler.ts";

Deno.serve(createMedicationLabelHandler({
  env: {
    supabaseUrl: (Deno.env.get("SUPABASE_URL") ?? "").replace(/\/$/, ""),
    anonKey: Deno.env.get("SUPABASE_ANON_KEY") ?? "",
    serviceKey: Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "",
    nvidiaKey: Deno.env.get("NVIDIA_API_KEY") ?? "",
    model: Deno.env.get("NVIDIA_VISION_MODEL") ?? "nvidia/nemotron-3.5-super-vl-120b-a12b",
  },
  fetch,
}));
