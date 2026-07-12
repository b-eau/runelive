// Personalized conversation starters for the sidekick chat and each profile
// tab. Responds instantly from the durable cache (or grounded heuristics),
// and regenerates via the LLM in the background so the slow call never
// blocks the response.

import { after, NextRequest, NextResponse } from "next/server";
import { authorizedProfile } from "@/lib/data";
import {
  peekSuggestions,
  refreshSuggestions,
  SUGGEST_CONTEXTS,
  type SuggestContext,
} from "@/lib/suggest";

export const maxDuration = 30;

export async function GET(req: NextRequest) {
  const profileId = req.nextUrl.searchParams.get("profileId");
  if (!profileId) return NextResponse.json({ error: "profileId required" }, { status: 400 });
  const profile = await authorizedProfile(profileId);
  if (!profile) return NextResponse.json({ error: "Not found" }, { status: 404 });

  const raw = req.nextUrl.searchParams.get("context");
  const context: SuggestContext = SUGGEST_CONTEXTS.includes(raw as SuggestContext)
    ? (raw as SuggestContext)
    : "chat";

  const { suggestions, needsRefresh } = await peekSuggestions(profileId, context);
  if (needsRefresh) {
    // Runs after the response is sent (Vercel waitUntil); the model call
    // never blocks this request.
    after(() => refreshSuggestions(profileId, context).catch(() => {}));
  }

  return NextResponse.json(
    { suggestions },
    { headers: { "Cache-Control": "private, max-age=120" } },
  );
}
