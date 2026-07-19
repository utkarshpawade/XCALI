"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { api, getErrorMessage } from "../lib/api";
import { getToken } from "../lib/auth";
import { ChatRoomClient, type ChatMessage } from "./ChatRoomClient";

/**
 * Resolves the room slug to its id and loads the message history. The request
 * needs the caller's token, so it runs on the client rather than the server.
 */
export function ChatRoom({ slug }: { slug: string }) {
  const [roomId, setRoomId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      if (!getToken()) {
        setError("You are not signed in. Go back and sign in first.");
        setLoading(false);
        return;
      }

      try {
        const roomRes = await api.get(`/room/${encodeURIComponent(slug)}`);
        const id = String(roomRes.data.room.id);
        const chatRes = await api.get(`/chats/${id}`);

        if (cancelled) return;
        setRoomId(id);
        setMessages(chatRes.data.messages ?? []);
      } catch (err) {
        if (!cancelled) setError(getErrorMessage(err, "Could not open this room"));
      } finally {
        if (!cancelled) setLoading(false);
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [slug]);

  if (loading) {
    return <div style={{ padding: 24 }}>Loading room...</div>;
  }

  if (error || !roomId) {
    return (
      <div style={{ padding: 24 }}>
        <p>{error ?? "Room not found"}</p>
        <Link href="/">Back to home</Link>
      </div>
    );
  }

  return <ChatRoomClient id={roomId} slug={slug} messages={messages} />;
}
