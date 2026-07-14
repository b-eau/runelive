"use client";

// Desktop sidebar: brand, profile identity + switcher, primary navigation,
// recent Sidekick conversations (one click to resume), and sign out.

import Link from "next/link";
import { usePathname } from "next/navigation";
import ProfileSwitcher from "./ProfileSwitcher";
import { NAV_ITEMS, isNavActive, navHref } from "./nav";
import { timeAgo } from "@/lib/timeAgo";

export type RecentConversation = { id: string; title: string; updatedAt: string };

export default function SideNav({
  profileId,
  displayName,
  kindLabel,
  accountTypeLabel,
  lastSyncedAt,
  options,
  recent,
}: {
  profileId: string;
  displayName: string;
  kindLabel: string;
  accountTypeLabel: string;
  lastSyncedAt: string | null;
  options: { id: string; label: string }[];
  recent: RecentConversation[];
}) {
  const pathname = usePathname();

  return (
    <aside className="side">
      <Link href="/dashboard" className="side-brand">
        <span className="brand-mark" aria-hidden>
          ⚔
        </span>
        OSRS Sidekick
      </Link>

      <div className="side-profile">
        <div>
          <div className="side-profile-name">{displayName}</div>
          <div style={{ display: "flex", gap: 5, marginTop: 5, flexWrap: "wrap" }}>
            <span className="pill gold">{kindLabel}</span>
            <span className="pill">{accountTypeLabel}</span>
          </div>
        </div>
        {lastSyncedAt && <span className="side-sync">Synced {timeAgo(lastSyncedAt)}</span>}
        <ProfileSwitcher current={profileId} options={options} />
      </div>

      <nav className="side-nav" aria-label="Profile sections">
        {NAV_ITEMS.map((item) => (
          <Link
            key={item.slug}
            href={navHref(profileId, item.slug)}
            className={`side-link ${isNavActive(pathname, profileId, item.slug) ? "active" : ""}`}
          >
            <span className="ico" aria-hidden>
              {item.icon}
            </span>
            {item.label}
          </Link>
        ))}
      </nav>

      {recent.length > 0 && (
        <>
          <div className="side-section">
            <span className="side-section-label">Recent chats</span>
          </div>
          <nav className="side-nav" aria-label="Recent conversations">
            {recent.map((c) => (
              <Link key={c.id} href={`/p/${profileId}/chat?c=${c.id}`} className="side-conv" title={c.title}>
                {c.title}
                <span className="when">{timeAgo(c.updatedAt)}</span>
              </Link>
            ))}
          </nav>
        </>
      )}

      <div className="side-foot">
        <form action="/api/auth/signout" method="post" style={{ flex: 1 }}>
          <button className="btn ghost" type="submit" style={{ width: "100%", fontSize: 13 }}>
            Sign out
          </button>
        </form>
      </div>
    </aside>
  );
}
