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

export type RoomVisibility = "PUBLIC" | "PRIVATE";
export type RoomMemberRole = "OWNER" | "EDITOR" | "VIEWER";

export interface Room {
  id: string;
  name: string;
  description: string | null;
  visibility: RoomVisibility;
  inviteCode: string;
  ownerId: string;
  ownerName: string;
  currentUserRole: RoomMemberRole | null;
  createdAt: string;
  updatedAt: string;
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

export async function fetchMyRooms() {
  const { data } = await api.get<Room[]>("/rooms");
  return data;
}

export async function fetchPublicRooms() {
  const { data } = await api.get<Room[]>("/rooms/public");
  return data;
}

export async function fetchRoom(roomId: string) {
  const { data } = await api.get<Room>(`/rooms/${roomId}`);
  return data;
}

export async function createRoom(payload: {
  name: string;
  description?: string;
  visibility: RoomVisibility;
}) {
  const { data } = await api.post<Room>("/rooms", payload);
  return data;
}

export async function joinRoom(inviteCode: string) {
  const { data } = await api.post<Room>(`/rooms/join/${inviteCode}`);
  return data;
}

export type RunLanguage = "PYTHON";

export interface RunRequest {
  language: RunLanguage;
  code: string;
  stdin?: string;
}

export interface RunResult {
  stdout: string;
  stderr: string;
  exitCode: number;
  durationMs: number;
  timedOut: boolean;
}

export async function runCode(roomId: string, payload: RunRequest) {
  const { data } = await api.post<RunResult>(`/rooms/${roomId}/run`, payload);
  return data;
}

export interface RoomFile {
  id: string;
  path: string;
  language: string;
  createdAt: string;
  updatedAt: string;
}

export async function listRoomFiles(roomId: string) {
  const { data } = await api.get<RoomFile[]>(`/rooms/${roomId}/files`);
  return data;
}

export async function createRoomFile(roomId: string, path: string) {
  const { data } = await api.post<RoomFile>(`/rooms/${roomId}/files`, { path });
  return data;
}

export async function renameRoomFile(roomId: string, fileId: string, path: string) {
  const { data } = await api.patch<RoomFile>(`/rooms/${roomId}/files/${fileId}`, { path });
  return data;
}

export async function deleteRoomFile(roomId: string, fileId: string) {
  await api.delete(`/rooms/${roomId}/files/${fileId}`);
}

export interface GithubImportedFile {
  fileId: string;
  path: string;
  language: string;
  content: string;
}

export interface GithubImportSkippedFile {
  path: string;
  reason: string;
}

export interface GithubImportResponse {
  owner: string;
  repo: string;
  branchUsed: string;
  truncated: boolean;
  imported: GithubImportedFile[];
  skipped: GithubImportSkippedFile[];
}

export async function importGithub(
  roomId: string,
  payload: { repoUrl: string; branch?: string },
) {
  const { data } = await api.post<GithubImportResponse>(
    `/rooms/${roomId}/import/github`,
    payload,
  );
  return data;
}

export interface IndexResult {
  chunks: number;
  durationMs: number;
}

export async function indexRoom(
  roomId: string,
  payload: { path?: string; text: string },
) {
  const { data } = await api.post<IndexResult>(`/rooms/${roomId}/index`, payload);
  return data;
}

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";
