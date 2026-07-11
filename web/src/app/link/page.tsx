import { redirect } from "next/navigation";
import { currentUser } from "@/lib/auth";
import LinkClaim from "./LinkClaim";
import LinkByName from "./LinkByName";

export const metadata = { title: "Link your account" };

export default async function LinkPage({
  searchParams,
}: {
  searchParams: Promise<{ code?: string; username?: string }>;
}) {
  const params = await searchParams;
  const user = await currentUser();
  if (!user) {
    const query = new URLSearchParams();
    if (params.code) query.set("code", params.code);
    if (params.username) query.set("username", params.username);
    const qs = query.toString();
    redirect(`/signin?next=${encodeURIComponent(`/link${qs ? `?${qs}` : ""}`)}`);
  }

  const pluginCard = (
    <div className="card">
      <h2 style={{ fontSize: 18 }}>Link your RuneLite plugin</h2>
      <p className="sub">
        Confirm the code shown in your OSRS Sidekick plugin panel to connect this game account with
        full syncing — bank, quests, gear, and progress over time.
      </p>
      <LinkClaim initialCode={params.code ?? ""} />
    </div>
  );

  const usernameCard = (
    <div className="card">
      <h2 style={{ fontSize: 18 }}>No RuneLite plugin yet?</h2>
      <p className="sub">
        Link by username instead — Sidekick pulls your skills and boss kill counts from the public
        hiscores right away. When you connect the plugin later, everything carries over.
      </p>
      <LinkByName initialUsername={params.username ?? ""} />
    </div>
  );

  // Guests arriving from the "try it" flow carry ?username= — lead with the
  // path that finishes their transition in one click.
  const cards = params.username ? [usernameCard, pluginCard] : [pluginCard, usernameCard];

  return (
    <main className="shell" style={{ display: "grid", placeItems: "center", minHeight: "100dvh" }}>
      <div style={{ width: "100%", maxWidth: 420 }}>
        <p className="sub" style={{ textAlign: "center", marginBottom: 12 }}>
          Signed in as <strong>{user!.email}</strong>
        </p>
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {cards[0]}
          {cards[1]}
        </div>
      </div>
    </main>
  );
}
