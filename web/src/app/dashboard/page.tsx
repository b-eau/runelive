import Link from "next/link";
import { redirect } from "next/navigation";
import { currentUser } from "@/lib/auth";
import { userAccounts } from "@/lib/data";
import { ACCOUNT_TYPE_LABELS, PROFILE_KIND_LABELS } from "@/lib/osrs";
import { isRsnLinked } from "@/lib/rsnLink";

export const metadata = { title: "Dashboard" };

export default async function DashboardPage() {
  const user = await currentUser();
  if (!user) redirect("/signin");

  const accounts = await userAccounts();
  const profiles = accounts.flatMap((a) => a.profiles);
  if (profiles.length === 1) redirect(`/p/${profiles[0].id}`);

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link href="/" className="brand">
            <span className="brand-mark">⚔</span> OSRS Sidekick
          </Link>
          <div className="spacer" />
          <form action="/api/auth/signout" method="post">
            <button className="btn ghost" type="submit">
              Sign out
            </button>
          </form>
        </div>
      </header>
      <main className="shell">
        <h1 style={{ fontSize: 24, margin: "28px 0 4px", letterSpacing: "-0.02em" }}>Your characters</h1>
        <p className="sub" style={{ color: "var(--ink-3)", marginBottom: 20 }}>
          Pick a profile, or <Link href="/link">link another account</Link>.
        </p>
        {accounts.length === 0 ? (
          <div className="card empty">
            <p style={{ fontSize: 15, color: "var(--ink-2)" }}>No accounts linked yet.</p>
            <p>
              Install the <strong>OSRS Sidekick</strong> plugin in RuneLite and tick{" "}
              <strong>Link account</strong> in its settings — or start right now with just your username.
            </p>
            <Link href="/link" className="btn primary" style={{ marginTop: 12 }}>
              Link an account
            </Link>
          </div>
        ) : (
          <div className="grid cols-2">
            {accounts.map((account) =>
              account.profiles.map((profile) => (
                <Link key={profile.id} href={`/p/${profile.id}`} className="card" style={{ display: "block" }}>
                  <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
                    <div
                      className="brand-mark"
                      style={{ width: 44, height: 44, fontSize: 20, borderRadius: 12 }}
                      aria-hidden
                    >
                      {account.displayName.charAt(0).toUpperCase()}
                    </div>
                    <div style={{ flex: 1 }}>
                      <div style={{ fontWeight: 700, fontSize: 16 }}>{account.displayName}</div>
                      <div style={{ display: "flex", gap: 6, marginTop: 4, flexWrap: "wrap" }}>
                        <span className="pill gold">{PROFILE_KIND_LABELS[profile.kind] ?? profile.kind}</span>
                        <span className="pill">{ACCOUNT_TYPE_LABELS[profile.accountType] ?? profile.accountType}</span>
                        {profile.combatLevel && <span className="pill">Combat {profile.combatLevel}</span>}
                        {isRsnLinked(account.accountHash) && <span className="pill">Hiscores only</span>}
                      </div>
                    </div>
                    <span style={{ color: "var(--ink-3)" }}>→</span>
                  </div>
                </Link>
              )),
            )}
          </div>
        )}
      </main>
    </>
  );
}
