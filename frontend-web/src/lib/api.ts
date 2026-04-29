import axios from "axios";
import { useAuthStore } from "@/stores/auth-store";

export interface User {
  id: string;
  fullName: string;
  email: string;
  avatarUrl: string | null;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: User;
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1",
  headers: {
    "Content-Type": "application/json",
  },
});

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export async function registerUser(payload: {
  fullName: string;
  email: string;
  password: string;
}) {
  const { data } = await api.post<AuthResponse>("/auth/register", payload);
  return data;
}

export async function loginUser(payload: { email: string; password: string }) {
  const { data } = await api.post<AuthResponse>("/auth/login", payload);
  return data;
}

export async function fetchCurrentUser() {
  const { data } = await api.get<User>("/auth/me");
  return data;
}
