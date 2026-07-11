"use client";

// Sidekick chat + realtime voice. Voice mode uses the browser's Web Speech
// API: speech-to-text feeds the same tool-equipped chat backend, and replies
// are spoken with speechSynthesis while also appearing in the transcript.

import { useCallback, useEffect, useRef, useState } from "react";

type Msg = { id: string; role: "user" | "assistant"; content: string };

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

const SUGGESTIONS = [
  "What should I train next for my goals?",
  "Which quests am I missing for the quest cape?",
  "Do I have gear for a Vorkath trip?",
  "How much mining XP did I gain this month?",
];

export default function ChatPanel({
  profileId,
  displayName,
  demoMode,
}: {
  profileId: string;
  displayName: string;
  demoMode: boolean;
}) {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [voiceMode, setVoiceMode] = useState<"off" | "listening" | "speaking">("off");
  const [voiceSupported, setVoiceSupported] = useState(true);
  const [interim, setInterim] = useState("");
  const logRef = useRef<HTMLDivElement>(null);
  const recognizerRef = useRef<SpeechRecognitionLike | null>(null);
  const voiceModeRef = useRef(voiceMode);
  voiceModeRef.current = voiceMode;

  useEffect(() => {
    fetch(`/api/chat?profileId=${profileId}`)
      .then((r) => r.json())
      .then((d) => setMessages(d.messages ?? []))
      .catch(() => {});
  }, [profileId]);

  useEffect(() => {
    logRef.current?.scrollTo({ top: logRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, busy, interim]);

  const send = useCallback(
    async (text: string, spoken = false) => {
      const content = text.trim();
      if (!content || busy) return;
      setInput("");
      setInterim("");
      setBusy(true);
      setMessages((m) => [...m, { id: `local-${Date.now()}`, role: "user", content }]);
      try {
        const res = await fetch("/api/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ profileId, message: content }),
        });
        const body = await res.json();
        const reply: string = res.ok ? body.reply : (body.error ?? "Something went wrong.");
        setMessages((m) => [...m, { id: `local-${Date.now()}-a`, role: "assistant", content: reply }]);
        if (spoken && "speechSynthesis" in window) {
          setVoiceMode("speaking");
          const utterance = new SpeechSynthesisUtterance(reply.replace(/[*_#`]/g, "").slice(0, 1200));
          utterance.rate = 1.05;
          utterance.onend = () => {
            if (voiceModeRef.current !== "off") {
              setVoiceMode("listening");
              try {
                recognizerRef.current?.start();
              } catch {}
            }
          };
          window.speechSynthesis.speak(utterance);
        }
      } finally {
        setBusy(false);
      }
    },
    [busy, profileId],
  );

  const stopVoice = useCallback(() => {
    setVoiceMode("off");
    recognizerRef.current?.abort();
    if ("speechSynthesis" in window) window.speechSynthesis.cancel();
    setInterim("");
  }, []);

  const startVoice = useCallback(() => {
    const rec = getRecognizer();
    if (!rec || !("speechSynthesis" in window)) {
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
  }, [send, stopVoice]);

  useEffect(() => () => stopVoice(), [stopVoice]);

  return (
    <div className="card" style={{ display: "flex", flexDirection: "column", height: "min(72dvh, 720px)" }}>
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
        {messages.length === 0 && (
          <div className="empty" style={{ margin: "auto" }}>
            <div style={{ fontSize: 30, marginBottom: 10 }}>✨</div>
            <p>
              Ask me anything about <strong>{displayName}</strong> — training plans, quest routes, gear
              checks. I can see your synced stats, bank, quests, and kill counts.
            </p>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center", marginTop: 14 }}>
              {SUGGESTIONS.map((s) => (
                <button key={s} className="btn ghost" style={{ fontSize: 12.5, border: "1px solid var(--border)" }} onClick={() => void send(s)}>
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m) => (
          <div key={m.id} className={`msg ${m.role}`}>
            {m.content}
          </div>
        ))}
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
    </div>
  );
}
