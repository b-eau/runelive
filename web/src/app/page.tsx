import Link from "next/link";
import { redirect } from "next/navigation";
import { currentUser } from "@/lib/auth";

export default async function Landing() {
  const user = await currentUser();
  if (user) redirect("/dashboard");

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <div className="brand">
            <span className="brand-mark">⚔</span> OSRS Sidekick
          </div>
          <div className="spacer" />
          <Link href="/signin" className="btn">
            Sign in
          </Link>
        </div>
      </header>
      <main className="shell">
        <section className="hero">
          <h1>
            Your account, <span className="gold-text">understood.</span>
          </h1>
          <p>
            Sidekick syncs your Old School RuneScape progress straight from RuneLite — stats, quests, bank,
            boss KC — and pairs it with an AI guide that knows your goals.
          </p>
          <Link href="/signin" className="btn primary" style={{ fontSize: 16, padding: "12px 26px" }}>
            Get started — it&apos;s free
          </Link>
        </section>
        <section className="grid cols-3">
          <div className="card">
            <h3>📈 Every trend, tracked</h3>
            <p className="sub" style={{ marginBottom: 0 }}>
              XP per skill, bank value, boss kill counts — charted over time, down to XP-per-day analytics
              across years of play.
            </p>
          </div>
          <div className="card">
            <h3>🧠 A guide that knows you</h3>
            <p className="sub" style={{ marginBottom: 0 }}>
              Tell Sidekick your mission — “quest cape, as AFK as possible” — and chat or talk by voice with
              an assistant that can search your bank and read your quest log.
            </p>
          </div>
          <div className="card">
            <h3>🔗 One-click sync</h3>
            <p className="sub" style={{ marginBottom: 0 }}>
              Install the RuneLite plugin, click Link, sign in — done. Multiple accounts and per-mode
              profiles (main, leagues, deadman) all in one place.
            </p>
          </div>
        </section>
      </main>
    </>
  );
}
