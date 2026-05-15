import axios from "axios";
import { useAuthStore } from "@/stores/auth-store";

export type UserRole = "USER" | "ADMIN";

export interface User {
  id: string;
  fullName: string;
  email: string;
  avatarUrl: string | null;
  role: UserRole;
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
  fileCount: number;
  memberCount: number;
  pinned: boolean;
  archived: boolean;
  lastEditedById: string | null;
  lastEditedByName: string | null;
  createdAt: string;
  updatedAt: string;
}

export type RoomEventType =
  | "FILE_CREATED"
  | "FILE_RENAMED"
  | "FILE_DELETED"
  | "MEMBER_JOINED"
  | "CODE_RAN"
  | "AI_ASKED";

export interface RoomEvent {
  id: string;
  roomId: string;
  roomName: string;
  userId: string | null;
  userName: string | null;
  type: RoomEventType;
  payload: Record<string, string>;
  createdAt: string;
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

export interface OAuthProviders {
  providers: string[];
}

/**
 * Returns the list of OAuth providers the backend has client credentials
 * configured for. Used by the login / signup pages to decide whether to
 * render social-login buttons. The endpoint is unauthenticated.
 */
export async function fetchOAuthProviders() {
  const { data } = await api.get<OAuthProviders>("/auth/providers");
  return data;
}

export async function fetchMyRooms(includeArchived: boolean = false) {
  const { data } = await api.get<Room[]>("/rooms", {
    params: includeArchived ? { archived: true } : undefined,
  });
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
  templateId?: string;
}) {
  const { data } = await api.post<Room>("/rooms", payload);
  return data;
}

export interface ProjectTemplate {
  id: string;
  name: string;
  description: string;
  language: string;
  fileCount: number;
}

export async function fetchTemplates() {
  const { data } = await api.get<ProjectTemplate[]>("/templates");
  return data;
}

/**
 * Cross-room activity feed for the dashboard sidebar. Pass `since`
 * (an ISO timestamp) to fetch only events newer than what the client
 * already holds — used by the 30s poll to avoid re-pulling the page.
 */
export async function fetchActivity(since?: string) {
  const { data } = await api.get<RoomEvent[]>("/events", {
    params: since ? { since } : undefined,
  });
  return data;
}

export async function joinRoom(inviteCode: string) {
  const { data } = await api.post<Room>(`/rooms/join/${inviteCode}`);
  return data;
}

export async function pinRoom(roomId: string) {
  await api.post(`/rooms/${roomId}/pin`);
}

export async function unpinRoom(roomId: string) {
  await api.delete(`/rooms/${roomId}/pin`);
}

export async function archiveRoom(roomId: string) {
  const { data } = await api.post<Room>(`/rooms/${roomId}/archive`);
  return data;
}

export async function unarchiveRoom(roomId: string) {
  const { data } = await api.delete<Room>(`/rooms/${roomId}/archive`);
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

export interface IndexFile {
  path: string;
  text: string;
}

/**
 * Indexes every file in the room in one call. The caller gathers each
 * file's current text (including files with no open tab — the Y.Doc has
 * them all client-side) so RAG retrieval covers the whole project, not
 * just whatever tab happened to be open. Idempotent: re-indexing a path
 * replaces its chunks rather than duplicating them.
 */
export async function indexRoomAll(roomId: string, files: IndexFile[]) {
  const { data } = await api.post<IndexResult>(`/rooms/${roomId}/index/all`, { files });
  return data;
}

export const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080/api/v1";

// ===========================================================================
// Admin dashboard
// ===========================================================================

export type AuthMethod = "PASSWORD" | "GITHUB" | "GOOGLE";

export interface AdminUser {
  id: string;
  fullName: string;
  email: string;
  avatarUrl: string | null;
  role: UserRole;
  authMethod: AuthMethod;
  oauthProvider: string | null;
  oauthSubject: string | null;
  createdAt: string;
  updatedAt: string;
  ownedRoomsCount: number;
  memberRoomsCount: number;
}

export interface AdminRoom {
  id: string;
  name: string;
  description: string | null;
  visibility: RoomVisibility;
  inviteCode: string;
  ownerId: string | null;
  ownerEmail: string | null;
  ownerFullName: string | null;
  memberCount: number;
  fileCount: number;
  createdAt: string;
  updatedAt: string;
}

export interface AdminStats {
  totalUsers: number;
  usersByRole: Record<string, number>;
  usersByAuthMethod: Record<string, number>;
  usersJoinedLast7Days: number;
  totalRooms: number;
  roomsByVisibility: Record<string, number>;
  totalFiles: number;
  totalMembers: number;
  totalRagChunks: number;
  ragInfrastructureUp: boolean;
}

export async function fetchAdminUsers() {
  const { data } = await api.get<AdminUser[]>("/admin/users");
  return data;
}

export async function fetchAdminUser(userId: string) {
  const { data } = await api.get<AdminUser>(`/admin/users/${userId}`);
  return data;
}

export async function updateAdminUserRole(userId: string, role: UserRole) {
  const { data } = await api.patch<AdminUser>(`/admin/users/${userId}/role`, { role });
  return data;
}

export async function deleteAdminUser(userId: string) {
  await api.delete(`/admin/users/${userId}`);
}

export async function fetchAdminRooms() {
  const { data } = await api.get<AdminRoom[]>("/admin/rooms");
  return data;
}

export async function deleteAdminRoom(roomId: string) {
  await api.delete(`/admin/rooms/${roomId}`);
}

export async function fetchAdminStats() {
  const { data } = await api.get<AdminStats>("/admin/stats");
  return data;
}
