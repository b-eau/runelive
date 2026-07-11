"use client";

// Assistant chat bubble: renders the reply as formatted markdown with a
// subtle hover "copy" button that copies the raw markdown source.

import { useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

export default function AssistantMessage({ content }: { content: string }) {
  const [copied, setCopied] = useState(false);

  async function copy() {
    try {
      await navigator.clipboard.writeText(content);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // Clipboard unavailable (permissions/insecure context) — do nothing.
    }
  }

  return (
    <div className="msg assistant markdown">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown>
      <button
        type="button"
        className={`copy-btn ${copied ? "copied" : ""}`}
        onClick={() => void copy()}
        title="Copy raw markdown"
        aria-label="Copy message as markdown"
      >
        {copied ? "✓ Copied" : "Copy"}
      </button>
    </div>
  );
}
