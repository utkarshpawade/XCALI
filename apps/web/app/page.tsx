"use client";

import { FormEvent, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { api, getErrorMessage } from "../lib/api";
import { clearToken, getToken, setToken } from "../lib/auth";

export default function Home() {
  const [signedIn, setSignedIn] = useState(false);
  const [isSignup, setIsSignup] = useState(false);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [roomId, setRoomId] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const router = useRouter();

  useEffect(() => {
    setSignedIn(Boolean(getToken()));
  }, []);

  async function handleAuth(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setBusy(true);
    try {
      // Both endpoints hand back a token, so signup logs you straight in.
      const res = isSignup
        ? await api.post("/signup", { username: email, password, name })
        : await api.post("/signin", { username: email, password });
      setToken(res.data.token);
      setSignedIn(true);
    } catch (err) {
      setError(getErrorMessage(err, isSignup ? "Sign up failed" : "Sign in failed"));
    } finally {
      setBusy(false);
    }
  }

  function toggleMode() {
    setIsSignup((current) => !current);
    setError(null);
  }

  function handleJoin(e: FormEvent) {
    e.preventDefault();
    const slug = roomId.trim();
    if (slug) router.push(`/room/${encodeURIComponent(slug)}`);
  }

  function handleSignOut() {
    clearToken();
    setSignedIn(false);
  }

  return (
    <div
      style={{
        display: "flex",
        justifyContent: "center",
        alignItems: "center",
        minHeight: "100vh",
        padding: 24,
      }}
    >
      <div style={{ width: "100%", maxWidth: 360 }}>
        <h1 style={{ marginBottom: 16 }}>Chat rooms</h1>

        {error && <p style={{ color: "crimson" }}>{error}</p>}

        {signedIn ? (
          <>
            <form onSubmit={handleJoin} style={{ display: "flex", gap: 8 }}>
              <input
                value={roomId}
                onChange={(e) => setRoomId(e.target.value)}
                type="text"
                placeholder="Room name"
                required
                style={{ flex: 1, padding: 10 }}
              />
              <button type="submit" style={{ padding: 10 }}>
                Join room
              </button>
            </form>
            <button
              type="button"
              onClick={handleSignOut}
              style={{ marginTop: 12, padding: 8 }}
            >
              Sign out
            </button>
          </>
        ) : (
          <>
            <form onSubmit={handleAuth} style={{ display: "grid", gap: 8 }}>
              <p style={{ opacity: 0.7, margin: 0 }}>
                {isSignup
                  ? "Create an account - it works in the drawing app too."
                  : "Sign in with the account you created in the drawing app."}
              </p>
              {isSignup && (
                <input
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  type="text"
                  placeholder="Name"
                  autoComplete="name"
                  required
                  maxLength={50}
                  style={{ padding: 10 }}
                />
              )}
              <input
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                type="email"
                placeholder="Email"
                autoComplete="email"
                required
                style={{ padding: 10 }}
              />
              <input
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                type="password"
                placeholder="Password"
                autoComplete={isSignup ? "new-password" : "current-password"}
                required
                minLength={isSignup ? 6 : undefined}
                style={{ padding: 10 }}
              />
              {isSignup && (
                <p style={{ opacity: 0.7, margin: 0, fontSize: 13 }}>
                  Password must be at least 6 characters.
                </p>
              )}
              <button type="submit" disabled={busy} style={{ padding: 10 }}>
                {busy
                  ? isSignup
                    ? "Creating account..."
                    : "Signing in..."
                  : isSignup
                    ? "Sign up"
                    : "Sign in"}
              </button>
            </form>
            <p style={{ marginTop: 12, fontSize: 14 }}>
              {isSignup ? "Already have an account? " : "New here? "}
              <button
                type="button"
                onClick={toggleMode}
                style={{
                  background: "none",
                  border: "none",
                  padding: 0,
                  color: "inherit",
                  font: "inherit",
                  textDecoration: "underline",
                  cursor: "pointer",
                }}
              >
                {isSignup ? "Sign in" : "Create an account"}
              </button>
            </p>
          </>
        )}
      </div>
    </div>
  );
}
