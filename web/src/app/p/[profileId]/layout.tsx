import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { currentUser } from "@/lib/auth";
import { authorizedProfile, userAccounts } from "@/lib/data";
import { ACCOUNT_TYPE_LABELS, PROFILE_KIND_LABELS } from "@/lib/osrs";
import TabNav from "./TabNav";
import ProfileSwitcher from "./ProfileSwitcher";
import VoiceWidget from "@/components/VoiceWidget";

export default async function ProfileLayout({
  children,
  params,
}: {
  children: React.ReactNode;
  params: Promise<{ profileId: string }>;
}) {
  const user = await currentUser();
  if (!user) redirect("/signin");
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  const accounts = await userAccounts();
  const options = accounts.flatMap((a) =>
    a.profiles.map((p) => ({
      id: p.id,
      label: `${a.displayName} · ${PROFILE_KIND_LABELS[p.kind] ?? p.kind}`,
    })),
  );

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="topbar-inner">
          <Link href="/dashboard" className="brand">
            <span className="brand-mark">⚔</span>
            <span className="brand-name-full">OSRS Sidekick</span>
          </Link>
          <ProfileSwitcher current={profile.id} options={options} />
          <div className="spacer" />
          <span className="pill" title="Account type">
            {ACCOUNT_TYPE_LABELS[profile.accountType] ?? profile.accountType}
          </span>
          <form action="/api/auth/signout" method="post">
            <button className="btn ghost" type="submit">
              Sign out
            </button>
          </form>
        </div>
      </header>
      <main className="shell shell-profile">
        <div style={{ display: "flex", alignItems: "baseline", gap: 12, marginTop: 22, flexWrap: "wrap" }}>
          <h1 style={{ fontSize: 26, margin: 0, letterSpacing: "-0.02em" }}>{profile.account.displayName}</h1>
          <span className="pill gold">{PROFILE_KIND_LABELS[profile.kind] ?? profile.kind}</span>
          {profile.lastSyncedAt && (
            <span style={{ fontSize: 12.5, color: "var(--ink-3)" }}>
              Synced {profile.lastSyncedAt.toISOString().slice(0, 16).replace("T", " ")} UTC
            </span>
          )}
        </div>
        <TabNav profileId={profile.id} />
        {children}
      </main>
      <VoiceWidget profileId={profile.id} serverTts={!!process.env.ELEVENLABS_API_KEY} />
    </div>
  );
}
