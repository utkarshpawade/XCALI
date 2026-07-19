import { useEffect, useState } from "react";
import { WS_URL } from "../app/config";
import { getToken } from "../lib/auth";

export function useSocket() {
  const [loading, setLoading] = useState(true);
  const [socket, setSocket] = useState<WebSocket>();
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const token = getToken();

    if (!token) {
      setLoading(false);
      setError("You are not signed in.");
      return;
    }

    const ws = new WebSocket(`${WS_URL}?token=${encodeURIComponent(token)}`);

    ws.onopen = () => {
      setLoading(false);
      setError(null);
      setSocket(ws);
    };

    ws.onclose = (event) => {
      setSocket(undefined);
      setLoading(false);
      setError(
        event.code === 1008
          ? "Your session has expired."
          : "Lost the connection to the server.",
      );
    };

    return () => {
      ws.onopen = null;
      ws.onclose = null;
      if (
        ws.readyState === WebSocket.OPEN ||
        ws.readyState === WebSocket.CONNECTING
      ) {
        ws.close(1000, "Leaving room");
      }
    };
  }, []);

  return { socket, loading, error };
}
