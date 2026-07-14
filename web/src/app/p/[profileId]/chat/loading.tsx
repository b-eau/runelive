import { Sk } from "@/components/Skeleton";

// Mirrors the chat shell so the route paints instantly; the frame is
// height-locked by the same .chat-shell rule the real panel uses.
export default function ChatLoading() {
  return (
    <div className="chat-shell">
      <aside className="chat-rail">
        <Sk h={34} r={10} />
        {Array.from({ length: 5 }, (_, i) => (
          <Sk key={i} h={28} r={8} w={`${88 - ((i * 11) % 30)}%`} />
        ))}
      </aside>
      <section className="chat-main">
        <div className="chat-col">
          <div className="chat-log" />
          <div className="chat-composer" style={{ pointerEvents: "none" }}>
            <Sk h={38} w={38} r={12} />
            <div style={{ flex: 1, padding: "10px 2px" }}>
              <Sk h={16} w="45%" />
            </div>
            <Sk h={38} w={38} r={12} />
          </div>
        </div>
      </section>
    </div>
  );
}
