import Link from "next/link";
import { notFound, redirect } from "next/navigation";
import { currentUser } from "@/lib/auth";
import { db } from "@/lib/db";
import { authorizedProfile, userAccounts } from "@/lib/data";
import { ACCOUNT_TYPE_LABELS, PROFILE_KIND_LABELS } from "@/lib/osrs";
import SideNav from "./SideNav";
import MobileNav from "./MobileNav";
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

  const [accounts, recent] = await Promise.all([
    userAccounts(),
    db.conversation.findMany({
      where: { profileId },
      orderBy: { updatedAt: "desc" },
      take: 4,
      select: { id: true, title: true, updatedAt: true },
    }),
  ]);
  const options = accounts.flatMap((a) =>
    a.profiles.map((p) => ({
      id: p.id,
      label: `${a.displayName} · ${PROFILE_KIND_LABELS[p.kind] ?? p.kind}`,
    })),
  );

  const kindLabel = PROFILE_KIND_LABELS[profile.kind] ?? profile.kind;
  const accountTypeLabel = ACCOUNT_TYPE_LABELS[profile.accountType] ?? profile.accountType;

  return (
    <div className="app">
      <SideNav
        profileId={profile.id}
        displayName={profile.account.displayName}
        kindLabel={kindLabel}
        accountTypeLabel={accountTypeLabel}
        lastSyncedAt={profile.lastSyncedAt ? profile.lastSyncedAt.toISOString() : null}
        options={options}
        recent={recent.map((c) => ({ id: c.id, title: c.title, updatedAt: c.updatedAt.toISOString() }))}
      />
      <div className="frame">
        <header className="mtop">
          <Link href="/dashboard" aria-label="All characters">
            <span className="brand-mark" aria-hidden>
              ⚔
            </span>
          </Link>
          <span className="mtop-name">{profile.account.displayName}</span>
          <span className="pill gold">{kindLabel}</span>
          <div className="spacer" />
          <ProfileSwitcher current={profile.id} options={options} compact />
        </header>
        <main className="content">
          <div className="content-inner">{children}</div>
        </main>
        <MobileNav profileId={profile.id} />
      </div>
      <VoiceWidget profileId={profile.id} serverTts={!!process.env.ELEVENLABS_API_KEY} />
    </div>
  );
}
