"use client";

// Sidekick chat: parallel conversations with history, personalized starter
// prompts, markdown-rendered replies, and realtime voice. Voice mode uses
// the browser's Web Speech API: speech-to-text feeds the same tool-equipped
// chat backend, and replies are spoken with speechSynthesis while also
// appearing in the transcript.

import { useCallback, useEffect, useRef, useState } from "react";
import AssistantMessage from "@/components/AssistantMessage";

type Msg = { id: string; role: "user" | "assistant"; content: string };
type Conversation = { id: string; title: string; updatedAt: string };

type SpeechRecognitionLike = {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start: () => void;
  stop: () => void;
  abort: () => void;
  onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }> & { isFinal: boolean }>; resultIndex: number }) => void) | null;
  onend: (() => void) | null;
  onerror: ((e: { error: string }) => void) | null;
};

function getRecognizer(): SpeechRecognitionLike | null {
  if (typeof window === "undefined") return null;
  const w = window as unknown as {
    SpeechRecognition?: new () => SpeechRecognitionLike;
    webkitSpeechRecognition?: new () => SpeechRecognitionLike;
  };
  const Ctor = w.SpeechRecognition ?? w.webkitSpeechRecognition;
  return Ctor ? new Ctor() : null;
}

const FALLBACK_SUGGESTIONS = [
  "What should I train next for my goals?",
  "Which quests am I missing for the quest cape?",
  "How much XP did I gain this month?",
];

export default function ChatPanel({
  profileId,
  displayName,
  demoMode,
  serverTts = false,
}: {
  profileId: string;
  displayName: string;
  demoMode: boolean;
  /** True when the server offers ElevenLabs TTS (browser TTS is the fallback). */
  serverTts?: boolean;
}) {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [suggestions, setSuggestions] = useState<string[]>(FALLBACK_SUGGESTIONS);
  const [followups, setFollowups] = useState<string[]>([]);
  const [showSuggest, setShowSuggest] = useState(false);
  const [railOpen, setRailOpen] = useState(false);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [voiceMode, setVoiceMode] = useState<"off" | "listening" | "speaking">("off");
  const [voiceSupported, setVoiceSupported] = useState(true);
  const [interim, setInterim] = useState("");
  const logRef = useRef<HTMLDivElement>(null);
  const recognizerRef = useRef<SpeechRecognitionLike | null>(null);
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const voiceModeRef = useRef(voiceMode);
  voiceModeRef.current = voiceMode;

  useEffect(() => {
    fetch(`/api/conversations?profileId=${profileId}`)
      .then((r) => r.json())
      .then((d) => setConversations(d.conversations ?? []))
      .catch(() => {});
    fetch(`/api/chat/suggestions?profileId=${profileId}`)
      .then((r) => r.json())
      .then((d) => {
        if (Array.isArray(d.suggestions) && d.suggestions.length > 0) setSuggestions(d.suggestions);
      })
      .catch(() => {});
  }, [profileId]);

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, busy, interim]);

  const newChat = useCallback(() => {
    setActiveId(null);
    setMessages([]);
    setFollowups([]);
    setRailOpen(false);
    setShowSuggest(false);
  }, []);

  const selectConversation = useCallback(
    (id: string) => {
      setActiveId(id);
      setMessages([]);
      setFollowups([]);
      setRailOpen(false);
      setShowSuggest(false);
      fetch(`/api/chat?profileId=${profileId}&conversationId=${id}`)
        .then((r) => r.json())
        .then((d) => setMessages(d.messages ?? []))
        .catch(() => {});
    },
    [profileId],
  );

  const resumeListening = useCallback(() => {
    if (voiceModeRef.current !== "off") {
      setVoiceMode("listening");
      try {
        recognizerRef.current?.start();
      } catch {}
    }
  }, []);

  const browserSpeak = useCallback(
    (text: string) => {
      if (!("speechSynthesis" in window)) {
        resumeListening();
        return;
      }
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.rate = 1.05;
      utterance.onend = resumeListening;
      utterance.onerror = resumeListening;
      window.speechSynthesis.speak(utterance);
    },
    [resumeListening],
  );

  /** ElevenLabs via /api/tts when configured; browser speechSynthesis otherwise. */
  const speak = useCallback(
    async (reply: string) => {
      const text = reply.replace(/[*_#`]/g, "").slice(0, 1200);
      setVoiceMode("speaking");
      if (serverTts) {
        try {
          const res = await fetch("/api/tts", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ text }),
            signal: AbortSignal.timeout(20_000),
          });
          if (!res.ok) throw new Error(`tts ${res.status}`);
          const url = URL.createObjectURL(await res.blob());
          const audio = new Audio(url);
          audioRef.current = audio;
          const done = () => {
            URL.revokeObjectURL(url);
            if (audioRef.current === audio) audioRef.current = null;
            resumeListening();
          };
          audio.onended = done;
          audio.onerror = done;
          await audio.play();
          return;
        } catch {
          // Server TTS unavailable — fall through to the browser voice.
        }
      }
      browserSpeak(text);
    },
    [serverTts, browserSpeak, resumeListening],
  );

  const send = useCallback(
    async (text: string, spoken = false, conversationOverride?: string | null) => {
      const content = text.trim();
      if (!content || busy) return;
      const conversationId = conversationOverride !== undefined ? conversationOverride : activeId;
      if (conversationId !== activeId) {
        setActiveId(conversationId);
        setMessages([]);
      }
      setInput("");
      setInterim("");
      setShowSuggest(false);
      setFollowups([]);
      setBusy(true);
      setMessages((m) => [
        ...(conversationId === activeId ? m : []),
        { id: `local-${Date.now()}`, role: "user", content },
      ]);
      try {
        // Timeouts and non-JSON gateway errors must surface as a reply
        // bubble — an uncaught rejection here leaves the chat looking frozen.
        let reply: string;
        let returnedId: string | undefined;
        let returnedTitle: string | undefined;
        let returnedFollowups: string[] = [];
        try {
          const res = await fetch("/api/chat", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ profileId, message: content, ...(conversationId ? { conversationId } : {}) }),
            signal: AbortSignal.timeout(118_000),
          });
          const body = await res.json().catch(() => ({}));
          reply = res.ok
            ? (body.reply ?? "Something went wrong.")
            : (body.error ?? "Sidekick hit a snag answering that. Try again in a moment.");
          returnedId = body.conversationId;
          returnedTitle = body.title;
          if (Array.isArray(body.followups)) returnedFollowups = body.followups;
        } catch {
          reply = "That took too long and timed out — try asking again, or break the question up.";
        }
        setMessages((m) => [...m, { id: `local-${Date.now()}-a`, role: "assistant", content: reply }]);
        setFollowups(returnedFollowups);
        if (returnedId) {
          const id = returnedId;
          const title = returnedTitle ?? content.slice(0, 60);
          setActiveId(id);
          setConversations((list) => {
            const rest = list.filter((c) => c.id !== id);
            const existing = list.find((c) => c.id === id);
            return [{ id, title: existing?.title ?? title, updatedAt: new Date().toISOString() }, ...rest];
          });
        }
        if (spoken) {
          void speak(reply);
        }
      } finally {
        setBusy(false);
      }
    },
    [busy, profileId, activeId, speak],
  );

  const stopVoice = useCallback(() => {
    setVoiceMode("off");
    recognizerRef.current?.abort();
    if (audioRef.current) {
      audioRef.current.pause();
      audioRef.current = null;
    }
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
    setInterim("");
  }, []);

  const startVoice = useCallback(() => {
    const rec = getRecognizer();
    // Browser TTS is only required when the server doesn't provide it.
    if (!rec || (!serverTts && !("speechSynthesis" in window))) {
      setVoiceSupported(false);
      return;
    }
    recognizerRef.current = rec;
    rec.lang = "en-US";
    rec.continuous = false;
    rec.interimResults = true;
    rec.onresult = (e) => {
      let finalText = "";
      let interimText = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const result = e.results[i];
        if (result.isFinal) finalText += result[0].transcript;
        else interimText += result[0].transcript;
      }
      if (interimText) setInterim(interimText);
      if (finalText.trim()) {
        rec.stop();
        void send(finalText, true);
      }
    };
    rec.onend = () => {
      // Restart listening unless we're speaking or turned off.
      if (voiceModeRef.current === "listening") {
        try {
          rec.start();
        } catch {}
      }
    };
    rec.onerror = (e) => {
      if (e.error === "not-allowed" || e.error === "service-not-allowed") {
        setVoiceSupported(false);
        stopVoice();
      }
    };
    setVoiceMode("listening");
    try {
      rec.start();
    } catch {}
  }, [send, stopVoice, serverTts]);

  useEffect(() => () => stopVoice(), [stopVoice]);

  const activeTitle = conversations.find((c) => c.id === activeId)?.title;

  return (
    <div className="card chat-shell" style={{ height: "min(72dvh, 720px)" }}>
      <aside className={`chat-rail ${railOpen ? "open" : ""}`}>
        <button className="btn primary" style={{ fontSize: 12.5, padding: "8px 10px" }} onClick={newChat}>
          + New chat
        </button>
        {conversations.map((c) => (
          <button
            key={c.id}
            className={`conv ${c.id === activeId ? "active" : ""}`}
            onClick={() => selectConversation(c.id)}
            title={c.title}
          >
            {c.title}
          </button>
        ))}
        {conversations.length === 0 && (
          <p style={{ fontSize: 11.5, color: "var(--ink-3)", padding: "2px 4px" }}>
            Your conversations will appear here — revisit any of them later.
          </p>
        )}
      </aside>
      <section className="chat-main">
        <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 10 }}>
          <button
            type="button"
            className="btn ghost rail-toggle"
            style={{ fontSize: 12.5, padding: "4px 8px" }}
            onClick={() => setRailOpen((o) => !o)}
          >
            ☰ Chats
          </button>
          <span style={{ fontSize: 12.5, color: "var(--ink-3)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {activeId ? (activeTitle ?? "Conversation") : "New conversation"}
          </span>
        </div>
        {demoMode && (
          <div
            style={{
              background: "color-mix(in oklab, var(--warning) 12%, transparent)",
              border: "1px solid color-mix(in oklab, var(--warning) 35%, transparent)",
              borderRadius: 10,
              padding: "8px 12px",
              fontSize: 12.5,
              marginBottom: 12,
            }}
          >
            Demo mode: set <code>ANTHROPIC_API_KEY</code> or <code>GEMINI_API_KEY</code> in <code>web/.env</code> to unlock the full assistant.
          </div>
        )}
        <div className="chat-log" ref={logRef}>
          {messages.length === 0 && !busy && (
            <div className="empty" style={{ margin: "auto" }}>
              <div style={{ fontSize: 30, marginBottom: 10 }}>✨</div>
              <p>
                Ask me anything about <strong>{displayName}</strong> — training plans, quest routes, gear
                checks. I can see your synced stats, bank, quests, and kill counts.
              </p>
              <div style={{ display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center", marginTop: 14 }}>
                {suggestions.map((s) => (
                  <button
                    key={s}
                    className="btn ghost"
                    style={{ fontSize: 12.5, border: "1px solid var(--border)" }}
                    onClick={() => void send(s, false, null)}
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}
          {messages.map((m) =>
            m.role === "assistant" ? (
              <AssistantMessage key={m.id} content={m.content} />
            ) : (
              <div key={m.id} className="msg user">
                {m.content}
              </div>
            ),
          )}
          {followups.length > 0 && !busy && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6, alignSelf: "flex-start", maxWidth: "82%" }}>
              {followups.map((f) => (
                <button
                  key={f}
                  className="btn ghost"
                  style={{ fontSize: 12, border: "1px solid var(--border)", padding: "5px 10px" }}
                  onClick={() => void send(f)}
                >
                  {f}
                </button>
              ))}
            </div>
          )}
          {interim && (
            <div className="msg user" style={{ opacity: 0.6 }}>
              {interim}…
            </div>
          )}
          {busy && (
            <div className="msg assistant" aria-live="polite">
              <span className="thinking-dots">Thinking…</span>
            </div>
          )}
        </div>
        {showSuggest && (
          <div className="suggest-pop">
            <span style={{ fontSize: 11, color: "var(--ink-3)", padding: "0 2px" }}>
              Start a fresh conversation:
            </span>
            {suggestions.map((s) => (
              <button key={s} onClick={() => void send(s, false, null)}>
                {s}
              </button>
            ))}
          </div>
        )}
        <form
          onSubmit={(e) => {
            e.preventDefault();
            void send(input);
          }}
          style={{ display: "flex", gap: 10, alignItems: "center", marginTop: 12 }}
        >
          <button
            type="button"
            className={`mic-btn ${voiceMode !== "off" ? "live" : ""}`}
            onClick={() => (voiceMode === "off" ? startVoice() : stopVoice())}
            title={
              !voiceSupported
                ? "Voice not supported in this browser"
                : voiceMode === "off"
                  ? "Start voice conversation"
                  : "Stop voice conversation"
            }
            disabled={!voiceSupported}
            aria-label="Toggle voice conversation"
          >
            {voiceMode === "off" ? "🎙️" : voiceMode === "speaking" ? "🔊" : "🎙️"}
          </button>
          <button
            type="button"
            className="btn ghost"
            style={{ fontSize: 15, padding: "6px 9px", border: "1px solid var(--border)" }}
            onClick={() => setShowSuggest((s) => !s)}
            title="Suggested conversation starters"
            aria-label="Show suggested conversation starters"
            aria-expanded={showSuggest}
          >
            ✨
          </button>
          <input
            type="text"
            placeholder={
              voiceMode === "listening" ? "Listening… speak now" : "Ask your Sidekick anything…"
            }
            value={input}
            onChange={(e) => setInput(e.target.value)}
            disabled={busy}
          />
          <button className="btn primary" type="submit" disabled={busy || !input.trim()}>
            Send
          </button>
        </form>
        {!voiceSupported && (
          <p style={{ fontSize: 11.5, color: "var(--ink-3)", margin: "8px 0 0" }}>
            Voice mode needs microphone access and a browser with the Web Speech API (Chrome, Edge, Safari).
          </p>
        )}
      </section>
    </div>
  );
}
