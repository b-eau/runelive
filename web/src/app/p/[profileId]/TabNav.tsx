"use client";

import Link, { useLinkStatus } from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { slug: "", label: "Overview" },
  { slug: "skills", label: "Skills" },
  { slug: "quests", label: "Quests" },
  { slug: "bank", label: "Bank" },
  { slug: "bosses", label: "Bosses" },
  { slug: "achievements", label: "Achievements" },
  { slug: "chat", label: "Sidekick ✨" },
];

// Shows a spinner on the clicked tab while its page renders, so switching
// tabs feels acknowledged even when the server render is slow (cold start).
// useLinkStatus reads the nearest parent <Link>, so this only reflects tab
// navigations — never the in-page skill/boss/range refinements.
function TabLabel({ label }: { label: string }) {
  const { pending } = useLinkStatus();
  return (
    <>
      {label}
      {pending && <span className="tab-spinner" aria-label="Loading" />}
    </>
  );
}

export default function TabNav({ profileId }: { profileId: string }) {
  const pathname = usePathname();
  const base = `/p/${profileId}`;
  return (
    <nav className="tabs">
      {TABS.map((t) => {
        const href = t.slug ? `${base}/${t.slug}` : base;
        const active = t.slug ? pathname.startsWith(href) : pathname === base;
        return (
          <Link key={t.slug} href={href} className={active ? "active" : ""}>
            <TabLabel label={t.label} />
          </Link>
        );
      })}
    </nav>
  );
}
