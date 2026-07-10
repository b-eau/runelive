import { redirect } from "next/navigation";
import { currentUser } from "@/lib/auth";
import LinkClaim from "./LinkClaim";

export const metadata = { title: "Link your account" };

export default async function LinkPage({
  searchParams,
}: {
  searchParams: Promise<{ code?: string }>;
}) {
  const params = await searchParams;
  const user = await currentUser();
  if (!user) {
    const next = `/link${params.code ? `?code=${encodeURIComponent(params.code)}` : ""}`;
    redirect(`/signin?next=${encodeURIComponent(next)}`);
  }

  return (
    <main className="shell" style={{ display: "grid", placeItems: "center", minHeight: "100dvh" }}>
      <div style={{ width: "100%", maxWidth: 420 }}>
        <div className="card">
          <h2 style={{ fontSize: 18 }}>Link your RuneLite plugin</h2>
          <p className="sub">
            Signed in as <strong>{user!.email}</strong>. Confirm the code shown in your OSRS Sidekick
            plugin panel to connect this game account.
          </p>
          <LinkClaim initialCode={params.code ?? ""} />
        </div>
      </div>
    </main>
  );
}
