"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Loader2 } from "lucide-react";
import { WS_URL } from "@/config";
import { getToken } from "@/lib/auth";
import { Canvas } from "./Canvas";

type Status = "connecting" | "open" | "unauthorized" | "failed";

export function RoomCanvas({ roomId }: { roomId: string }) {
  const router = useRouter();
  const [socket, setSocket] = useState<WebSocket | null>(null);
  const [status, setStatus] = useState<Status>("connecting");

  useEffect(() => {
    const token = getToken();

    if (!token) {
      setStatus("unauthorized");
      router.replace("/signin");
      return;
    }

    const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}`);

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: "join_room", roomId }));
      setSocket(ws);
      setStatus("open");
    };

    ws.onerror = () => {
      setStatus("failed");
    };

    ws.onclose = (event) => {
      setSocket(null);
      // 1008 is what the server sends when it rejects the token.
      setStatus(event.code === 1008 ? "unauthorized" : "failed");
    };

    return () => {
      ws.onopen = null;
      ws.onerror = null;
      ws.onclose = null;
      if (
        ws.readyState === WebSocket.OPEN ||
        ws.readyState === WebSocket.CONNECTING
      ) {
        ws.close(1000, "Leaving room");
      }
    };
  }, [roomId, router]);

  if (status === "open" && socket) {
    return <Canvas roomId={roomId} socket={socket} />;
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4 bg-[#0f0f0f] px-4 text-center text-white">
      {status === "connecting" && (
        <>
          <Loader2 className="h-6 w-6 animate-spin text-white/70" />
          <p className="text-white/70">Connecting to the board...</p>
        </>
      )}

      {status === "unauthorized" && (
        <>
          <p className="text-white/80">Your session has expired.</p>
          <Link
            href="/signin"
            className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
          >
            Sign in again
          </Link>
        </>
      )}

      {status === "failed" && (
        <>
          <p className="text-white/80">Lost the connection to the drawing server.</p>
          <div className="flex gap-3">
            <button
              type="button"
              onClick={() => window.location.reload()}
              className="rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground"
            >
              Retry
            </button>
            <Link
              href="/rooms"
              className="rounded-lg border border-white/20 px-4 py-2 text-sm font-medium text-white/80"
            >
              Back to boards
            </Link>
          </div>
        </>
      )}
    </div>
  );
}
