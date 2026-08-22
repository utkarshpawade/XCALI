"use client";

import { FormEvent, useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2, LogOut, Plus, Users2 } from "lucide-react";
import {
  createRoom,
  getErrorMessage,
  getRoomBySlug,
  listRooms,
  type Room,
} from "@/lib/api";
import { clearToken, isAuthenticated } from "@/lib/auth";

const INPUT_CLASS =
  "w-full rounded-lg border border-input bg-background px-3 py-2 text-sm outline-none focus:ring-2 focus:ring-ring";

export default function RoomsPage() {
  const router = useRouter();
  const [checkedAuth, setCheckedAuth] = useState(false);
  const [rooms, setRooms] = useState<Room[]>([]);
  const [loadingRooms, setLoadingRooms] = useState(true);
  const [newRoom, setNewRoom] = useState("");
  const [joinSlug, setJoinSlug] = useState("");
  const [creating, setCreating] = useState(false);
  const [joining, setJoining] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Token lives in localStorage, so the guard has to run on the client.
  useEffect(() => {
    if (!isAuthenticated()) {
      router.replace("/signin");
      return;
    }
    setCheckedAuth(true);
  }, [router]);

  const refresh = useCallback(async () => {
    setLoadingRooms(true);
    try {
      setRooms(await listRooms());
    } catch (err) {
      setError(getErrorMessage(err, "Could not load your rooms"));
    } finally {
      setLoadingRooms(false);
    }
  }, []);

  useEffect(() => {
    if (checkedAuth) {
      void refresh();
    }
  }, [checkedAuth, refresh]);

  async function handleCreate(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setCreating(true);
    try {
      const room = await createRoom(newRoom.trim());
      router.push(`/canvas/${room.roomId}`);
    } catch (err) {
      setError(getErrorMessage(err, "Could not create the room"));
      setCreating(false);
    }
  }

  async function handleJoin(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setJoining(true);
    try {
      const room = await getRoomBySlug(joinSlug.trim());
      router.push(`/canvas/${room.id}`);
    } catch (err) {
      setError(getErrorMessage(err, "Could not find that room"));
      setJoining(false);
    }
  }

  function handleSignOut() {
    clearToken();
    router.replace("/signin");
  }

  if (!checkedAuth) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Loader2 className="h-6 w-6 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-muted/40">
      <div className="container mx-auto max-w-4xl px-4 py-12 sm:px-6">
        <div className="mb-8 flex items-center justify-between">
          <div>
            <h1 className="text-3xl font-bold text-foreground">Your boards</h1>
            <p className="mt-1 text-sm text-muted-foreground">
              Create a board or join one by name.
            </p>
          </div>
          <button
            onClick={handleSignOut}
            className="inline-flex items-center gap-2 rounded-lg border px-3 py-2 text-sm font-medium hover:bg-accent"
          >
            <LogOut className="h-4 w-4" />
            Sign out
          </button>
        </div>

        {error && (
          <p
            role="alert"
            className="mb-6 rounded-lg bg-destructive/10 px-3 py-2 text-sm text-destructive"
          >
            {error}
          </p>
        )}

        <div className="mb-10 grid gap-4 sm:grid-cols-2">
          <form
            onSubmit={handleCreate}
            className="rounded-2xl border bg-card p-6 shadow-sm"
          >
            <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold">
              <Plus className="h-5 w-5 text-primary" />
              New board
            </h2>
            <p className="mb-4 text-sm text-muted-foreground">
              3-20 characters: letters, numbers, - and _
            </p>
            <input
              value={newRoom}
              onChange={(e) => setNewRoom(e.target.value)}
              required
              minLength={3}
              maxLength={20}
              pattern="[a-zA-Z0-9\-_]+"
              placeholder="design-review"
              className={INPUT_CLASS}
            />
            <button
              type="submit"
              disabled={creating}
              className="mt-3 inline-flex w-full items-center justify-center rounded-lg bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:opacity-60"
            >
              {creating && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Create and open
            </button>
          </form>

          <form
            onSubmit={handleJoin}
            className="rounded-2xl border bg-card p-6 shadow-sm"
          >
            <h2 className="mb-1 flex items-center gap-2 text-lg font-semibold">
              <Users2 className="h-5 w-5 text-primary" />
              Join a board
            </h2>
            <p className="mb-4 text-sm text-muted-foreground">
              Enter the board name someone shared with you.
            </p>
            <input
              value={joinSlug}
              onChange={(e) => setJoinSlug(e.target.value)}
              required
              placeholder="design-review"
              className={INPUT_CLASS}
            />
            <button
              type="submit"
              disabled={joining}
              className="mt-3 inline-flex w-full items-center justify-center rounded-lg border px-4 py-2 text-sm font-medium hover:bg-accent disabled:opacity-60"
            >
              {joining && <Loader2 className="mr-2 h-4 w-4 animate-spin" />}
              Join
            </button>
          </form>
        </div>

        <h2 className="mb-3 text-lg font-semibold">Boards you created</h2>
        {loadingRooms ? (
          <div className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="h-4 w-4 animate-spin" />
            Loading...
          </div>
        ) : rooms.length === 0 ? (
          <p className="rounded-2xl border border-dashed bg-card p-8 text-center text-sm text-muted-foreground">
            No boards yet. Create your first one above.
          </p>
        ) : (
          <ul className="grid gap-3 sm:grid-cols-2">
            {rooms.map((room) => (
              <li key={room.id}>
                <button
                  onClick={() => router.push(`/canvas/${room.id}`)}
                  className="w-full rounded-xl border bg-card p-4 text-left transition-colors hover:border-primary"
                >
                  <span className="block font-medium text-foreground">{room.slug}</span>
                  <span className="block text-xs text-muted-foreground">
                    Created {new Date(room.createdAt).toLocaleDateString("en-GB")}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}
