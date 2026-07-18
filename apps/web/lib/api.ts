import axios, { AxiosError } from "axios";
import { BACKEND_URL } from "../app/config";
import { clearToken, getToken } from "./auth";

export const api = axios.create({
  baseURL: BACKEND_URL,
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
    if (error.response?.status === 401) {
      clearToken();
    }
    return Promise.reject(error);
  },
);

export function getErrorMessage(error: unknown, fallback = "Something went wrong"): string {
  if (axios.isAxiosError(error)) {
    if (!error.response) {
      return "Cannot reach the server. Is the backend running?";
    }
    const data = error.response.data as
      | { message?: string; errors?: Record<string, string> }
      | undefined;

    // Validation failures carry a per-field map; show the first one.
    const firstFieldError = data?.errors
      ? Object.values(data.errors)[0]
      : undefined;

    return firstFieldError ?? data?.message ?? fallback;
  }
  if (error instanceof Error) return error.message;
  return fallback;
}
