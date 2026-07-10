"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const TABS = [
  { slug: "", label: "Overview" },
  { slug: "skills", label: "Skills" },
  { slug: "quests", label: "Quests" },
  { slug: "bank", label: "Bank" },
  { slug: "bosses", label: "Bosses" },
  { slug: "chat", label: "Sidekick ✨" },
];

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
            {t.label}
          </Link>
        );
      })}
    </nav>
  );
}
