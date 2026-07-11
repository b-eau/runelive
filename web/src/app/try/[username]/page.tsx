import Link from "next/link";
import GuestExperience from "./GuestExperience";

export async function generateMetadata({ params }: { params: Promise<{ username: string }> }) {
  const { username } = await params;
  return { title: `${decodeURIComponent(username)} · Sidekick preview` };
}

export default async function TryPage({ params }: { params: Promise<{ username: string }> }) {
  const { username } = await params;
  // After sign-up, land on /link with this username prefilled.
  const signupHref = `/signin?next=${encodeURIComponent(
    `/link?username=${encodeURIComponent(decodeURIComponent(username))}`,
  )}`;

  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <Link href="/" className="brand">
            <span className="brand-mark">⚔</span> OSRS Sidekick
          </Link>
          <span className="pill gold">Guest preview</span>
          <div className="spacer" />
          <Link href={signupHref} className="btn primary">
            Sign up free
          </Link>
        </div>
      </header>
      <main className="shell">
        <GuestExperience username={decodeURIComponent(username)} />
      </main>
    </>
  );
}
