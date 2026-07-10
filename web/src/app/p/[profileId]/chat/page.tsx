import { notFound } from "next/navigation";
import { authorizedProfile } from "@/lib/data";
import { anthropicEnabled } from "@/lib/sidekick";
import ChatPanel from "./ChatPanel";

export const metadata = { title: "Sidekick" };

export default async function ChatPage({ params }: { params: Promise<{ profileId: string }> }) {
  const { profileId } = await params;
  const profile = await authorizedProfile(profileId);
  if (!profile) notFound();

  return (
    <ChatPanel
      profileId={profileId}
      displayName={profile.account.displayName}
      demoMode={!anthropicEnabled()}
    />
  );
}
