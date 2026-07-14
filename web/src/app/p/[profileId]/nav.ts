// Shared destination list for the profile app's sidebar (desktop) and
// bottom tab bar (mobile).

export type NavItem = { slug: string; label: string; icon: string };

export const NAV_ITEMS: NavItem[] = [
  { slug: "", label: "Overview", icon: "🏠" },
  { slug: "skills", label: "Skills", icon: "📊" },
  { slug: "quests", label: "Quests", icon: "🗺️" },
  { slug: "bank", label: "Bank", icon: "💰" },
  { slug: "bosses", label: "Bosses", icon: "☠️" },
  { slug: "achievements", label: "Achievements", icon: "🏆" },
  { slug: "chat", label: "Sidekick", icon: "✨" },
];

export function navHref(profileId: string, slug: string): string {
  return slug ? `/p/${profileId}/${slug}` : `/p/${profileId}`;
}

export function isNavActive(pathname: string, profileId: string, slug: string): boolean {
  const href = navHref(profileId, slug);
  return slug ? pathname.startsWith(href) : pathname === href;
}
