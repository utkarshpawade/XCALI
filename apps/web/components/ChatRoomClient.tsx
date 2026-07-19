"use client";

import { FormEvent, useEffect, useState } from "react";
import { useSocket } from "../hooks/useSocket";

export interface ChatMessage {
  id?: number;
  message: string;
}

export function ChatRoomClient({
  messages,
  id,
  slug,
}: {
  messages: ChatMessage[];
  id: string;
  slug?: string;
}) {
  const [chats, setChats] = useState<ChatMessage[]>(messages);
  const [currentMessage, setCurrentMessage] = useState("");
  const { socket, loading, error } = useSocket();

  useEffect(() => {
    if (!socket || loading) return;

    socket.send(JSON.stringify({ type: "join_room", roomId: id }));

    socket.onmessage = (event) => {
      try {
        const parsedData = JSON.parse(event.data);
        if (parsedData.type === "chat") {
          setChats((c) => [...c, { message: parsedData.message }]);
        }
      } catch {
        // Ignore malformed frames.
      }
    };

    return () => {
      socket.onmessage = null;
    };
  }, [socket, loading, id]);

  function handleSend(e: FormEvent) {
    e.preventDefault();
    const text = currentMessage.trim();
    if (!text || !socket) return;

    socket.send(JSON.stringify({ type: "chat", roomId: id, message: text }));
    // The server does not echo back to the sender, so append locally.
    setChats((c) => [...c, { message: text }]);
    setCurrentMessage("");
  }

  return (
    <div style={{ maxWidth: 640, margin: "0 auto", padding: 24 }}>
      <h1 style={{ marginBottom: 16 }}>Room {slug ?? id}</h1>

      {error && <p style={{ color: "crimson" }}>{error}</p>}

      <div
        style={{
          border: "1px solid #ddd",
          borderRadius: 8,
          padding: 12,
          minHeight: 240,
          marginBottom: 12,
        }}
      >
        {chats.length === 0 ? (
          <p style={{ opacity: 0.6 }}>No messages yet.</p>
        ) : (
          chats.map((m, index) => (
            <div key={m.id ?? `local-${index}`}>{m.message}</div>
          ))
        )}
      </div>

      <form onSubmit={handleSend} style={{ display: "flex", gap: 8 }}>
        <input
          type="text"
          value={currentMessage}
          onChange={(e) => setCurrentMessage(e.target.value)}
          placeholder="Type a message"
          style={{ flex: 1, padding: 8 }}
        />
        <button type="submit" disabled={!socket || loading} style={{ padding: 8 }}>
          Send
        </button>
      </form>
    </div>
  );
}
