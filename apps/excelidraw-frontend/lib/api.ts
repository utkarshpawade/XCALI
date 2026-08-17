import axios, { AxiosError } from "axios";
import { HTTP_BACKEND } from "@/config";
import { clearToken, getToken } from "./auth";

export const api = axios.create({
  baseURL: HTTP_BACKEND,
  headers: { "Content-Type": "application/json" },
});

api.interceptors.request.use((config) => {
  const token = getToken();
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    // A rejected token is stale - drop it so the UI can send the user to signin.
    if (error.response?.status === 401) {
      clearToken();
    }
    return Promise.reject(error);
  },
);

/** Pulls a human readable message out of an axios error. */
export function getErrorMessage(error: unknown, fallback = "Something went wrong"): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return "Cannot reach the server. Is the backend running?";
    }
    const data = error.response.data as
      | { message?: string; errors?: Record<string, string> }
      | undefined;

    const firstFieldError = data?.errors
      ? Object.values(data.errors)[0]
      : undefined;

    return firstFieldError ?? data?.message ?? fallback;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return fallback;
}

export interface Room {
  id: number;
  slug: string;
  createdAt: string;
}

export async function signup(input: {
  username: string;
  password: string;
  name: string;
}): Promise<{ token: string }> {
  const res = await api.post("/signup", input);
  return res.data;
}

export async function signin(input: {
  username: string;
  password: string;
}): Promise<{ token: string }> {
  const res = await api.post("/signin", input);
  return res.data;
}

export async function listRooms(): Promise<Room[]> {
  const res = await api.get("/rooms");
  return res.data.rooms ?? [];
}

export async function createRoom(name: string): Promise<{ roomId: number; slug: string }> {
  const res = await api.post("/room", { name });
  return res.data;
}

export async function getRoomBySlug(slug: string): Promise<Room> {
  const res = await api.get(`/room/${encodeURIComponent(slug)}`);
  return res.data.room;
}
