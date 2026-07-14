"use client";

// Mobile bottom tab bar: the four highest-traffic destinations plus a
// "More" sheet for the rest (quests, bosses, achievements, account actions).

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { isNavActive, navHref } from "./nav";

const PRIMARY = [
  { slug: "", label: "Home", icon: "🏠" },
  { slug: "skills", label: "Skills", icon: "📊" },
  { slug: "chat", label: "Sidekick", icon: "✨" },
  { slug: "bank", label: "Bank", icon: "💰" },
] as const;

const MORE = [
  { slug: "quests", label: "Quests", icon: "🗺️" },
  { slug: "bosses", label: "Bosses", icon: "☠️" },
  { slug: "achievements", label: "Achievements", icon: "🏆" },
] as const;

export default function MobileNav({ profileId }: { profileId: string }) {
  const pathname = usePathname();
  const [moreOpen, setMoreOpen] = useState(false);

  // Any navigation closes the sheet.
  useEffect(() => {
    setMoreOpen(false);
  }, [pathname]);

  const moreActive = MORE.some((item) => isNavActive(pathname, profileId, item.slug));

  return (
    <>
      {moreOpen && (
        <>
          <button className="more-backdrop" aria-label="Close menu" onClick={() => setMoreOpen(false)} />
          <div className="more-sheet" role="menu" aria-label="More sections">
            {MORE.map((item) => (
              <Link
                key={item.slug}
                href={navHref(profileId, item.slug)}
                className={`more-item ${isNavActive(pathname, profileId, item.slug) ? "active" : ""}`}
                role="menuitem"
              >
                <span className="ico" aria-hidden>
                  {item.icon}
                </span>
                {item.label}
              </Link>
            ))}
            <div className="more-sep" aria-hidden />
            <Link href="/dashboard" className="more-item" role="menuitem">
              <span className="ico" aria-hidden>
                👥
              </span>
              All characters
            </Link>
            <form action="/api/auth/signout" method="post">
              <button className="more-item" type="submit" role="menuitem">
                <span className="ico" aria-hidden>
                  🚪
                </span>
                Sign out
              </button>
            </form>
          </div>
        </>
      )}
      <nav className="mnav" aria-label="Primary">
        {PRIMARY.map((item) => (
          <Link
            key={item.slug}
            href={navHref(profileId, item.slug)}
            className={`mnav-item ${!moreOpen && isNavActive(pathname, profileId, item.slug) ? "active" : ""}`}
          >
            <span className="ico" aria-hidden>
              {item.icon}
            </span>
            {item.label}
          </Link>
        ))}
        <button
          type="button"
          className={`mnav-item ${moreOpen || moreActive ? "active" : ""}`}
          onClick={() => setMoreOpen((o) => !o)}
          aria-expanded={moreOpen}
          aria-label="More sections"
        >
          <span className="ico" aria-hidden>
            ⋯
          </span>
          More
        </button>
      </nav>
    </>
  );
}
