import axios, { type AxiosError, type InternalAxiosRequestConfig } from "axios";
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

export function getApiErrorMessage(error: unknown, fallback = "Request failed") {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as
      | { message?: string; validationErrors?: Record<string, string> }
      | undefined;
    const validationErrors = data?.validationErrors
      ? Object.entries(data.validationErrors)
      : [];
    if (validationErrors.length > 0) {
      const details = validationErrors
        .map(([field, message]) => `${field}: ${message}`)
        .join("; ");
      return data?.message ? `${data.message}: ${details}` : details;
    }
    return data?.message ?? error.message;
  }
  return error instanceof Error ? error.message : fallback;
}

api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken;
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// -----------------------------------------------------------------------------
// 401 response interceptor — silent refresh + retry, logout on failure
// -----------------------------------------------------------------------------
// Without this, an expired access token (15-min TTL) leaves the user staring
// at a dashboard frozen on stale localStorage data — every protected call
// returns 401 and the UI never gets a signal to recover. The refresh token
// lives in the same persisted store; we trade it for a fresh access token
// transparently on the first 401, then re-issue the failed request.
//
// Concurrent 401s are coalesced behind a single in-flight refresh promise
// so a dashboard mount that fires 4-5 simultaneous queries doesn't fan out
// into 5 parallel refresh calls (which would race the token-rotation).

type RetryableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

let refreshInFlight: Promise<string> | null = null;

function isAuthEndpoint(url: string | undefined): boolean {
  if (!url) return false;
  return url.includes("/auth/refresh") || url.includes("/auth/login") || url.includes("/auth/register");
}

async function refreshAccessToken(): Promise<string> {
  if (refreshInFlight) return refreshInFlight;
  const current = useAuthStore.getState();
  if (!current.refreshToken) {
    return Promise.reject(new Error("no refresh token"));
  }
  refreshInFlight = (async () => {
    try {
      // Use a bare axios call here, not `api`, so we don't recurse through
      // our own interceptors on the refresh endpoint itself.
      const { data } = await axios.post<AuthResponse>(
        `${api.defaults.baseURL}/auth/refresh`,
        { refreshToken: current.refreshToken },
        { headers: { "Content-Type": "application/json" } },
      );
      useAuthStore.getState().setSession({
        accessToken: data.accessToken,
        refreshToken: data.refreshToken,
        user: data.user,
      });
      return data.accessToken;
    } finally {
      refreshInFlight = null;
    }
  })();
  return refreshInFlight;
}

api.interceptors.response.use(
  (response) => response,
  async (error: AxiosError) => {
    const original = error.config as RetryableConfig | undefined;
    const status = error.response?.status;

    // Only intervene on 401, and only once per request, and not on the
    // auth endpoints themselves (refreshing a login attempt is nonsense).
    if (status !== 401 || !original || original._retried || isAuthEndpoint(original.url)) {
      return Promise.reject(error);
    }
    original._retried = true;

    try {
      const newToken = await refreshAccessToken();
      original.headers = original.headers ?? {};
      original.headers.Authorization = `Bearer ${newToken}`;
      return api(original);
    } catch (refreshErr) {
      // Refresh failed — wipe the store so ProtectedRoute can redirect
      // to /login. We rethrow the ORIGINAL error so the caller still sees
      // the 401 it was expecting; the refresh error is logged only.
      // eslint-disable-next-line no-console
      console.warn("token refresh failed, logging out", refreshErr);
      useAuthStore.getState().logout();
      return Promise.reject(error);
    }
  },
);

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

export interface OAuthAccount {
  provider: string;
  email: string | null;
  scopes: string | null;
  expiresAt: string | null;
  updatedAt: string;
}

export async function fetchOAuthAccounts() {
  const { data } = await api.get<OAuthAccount[]>("/users/me/oauth-accounts");
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

export async function saveRoomSnapshot(roomId: string, update: Uint8Array) {
  await api.put(`/rooms/${roomId}/snapshot`, update, {
    headers: {
      "Content-Type": "application/octet-stream",
    },
  });
}

export interface ProjectTemplate {
  id: string;
  name: string;
  description: string;
  language: string;
  category: string;
  runtime: string | null;
  packageManager: string | null;
  defaultCommand: string | null;
  runnable: boolean;
  preview: boolean;
  services: string[];
  tags: string[];
  fileCount: number;
}

export interface ProjectTemplateFile {
  path: string;
  content?: string | null;
}

export interface ProjectTemplateDetail extends ProjectTemplate {
  files: ProjectTemplateFile[];
}

export async function fetchTemplates() {
  const { data } = await api.get<ProjectTemplate[]>("/templates");
  return data;
}

export async function fetchTemplate(templateId: string) {
  const { data } = await api.get<ProjectTemplateDetail>(`/templates/${templateId}`);
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

export type RunLanguage = "PYTHON" | "JAVA";

export interface RunRequest {
  language: RunLanguage;
  code: string;
  filename?: string;
  stdin?: string;
  files?: RunProjectFile[];
}

export interface RunProjectFile {
  path: string;
  text: string;
}

export interface ProjectRunRequest {
  command?: string;
  files: RunProjectFile[];
}

export interface RunResult {
  stdout: string;
  stderr: string;
  exitCode: number;
  durationMs: number;
  timedOut: boolean;
}

export interface ProjectRunResult extends RunResult {
  environment: string;
  command: string;
  generatedEnvironment: boolean;
  fileCount: number;
  timeoutMs: number;
  runnerImage: string;
  cacheVolumes: string[];
  services: string[];
}

export interface ProjectRunDetection {
  runnable: boolean;
  environment: string | null;
  command: string | null;
  generatedEnvironment: boolean;
  services: string[];
  message: string | null;
}

export async function runCode(roomId: string, payload: RunRequest) {
  const { data } = await api.post<RunResult>(`/rooms/${roomId}/run`, payload);
  return data;
}

export async function runProject(roomId: string, payload: ProjectRunRequest) {
  const { data } = await api.post<ProjectRunResult>(`/rooms/${roomId}/run/project`, payload);
  return data;
}

export async function detectProjectRun(roomId: string, payload: ProjectRunRequest) {
  const { data } = await api.post<ProjectRunDetection>(
    `/rooms/${roomId}/run/project/detect`,
    payload,
  );
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

export interface GithubRepository {
  fullName: string;
  owner: string | null;
  name: string | null;
  htmlUrl: string | null;
  defaultBranch: string | null;
  privateRepo: boolean;
  description: string | null;
  updatedAt: string | null;
}

export async function fetchGithubRepositories() {
  const { data } = await api.get<GithubRepository[]>("/github/repositories");
  return data;
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

export type ChatHistoryRole = "USER" | "ASSISTANT";

export interface ChatHistoryEntry {
  id: string;
  userId: string | null;
  userName: string | null;
  role: ChatHistoryRole;
  content: string;
  createdAt: string;
}

/**
 * Returns a persisted AI chat in this room (oldest first).
 *
 * When called with no userId the caller gets their own thread — the
 * common case. The room owner can additionally pass another member's
 * userId to read that member's thread; non-owners passing a foreign
 * userId get a 403 from the backend.
 */
export async function fetchChatHistory(roomId: string, userId?: string) {
  const { data } = await api.get<ChatHistoryEntry[]>(`/rooms/${roomId}/chat/history`, {
    params: userId ? { userId } : undefined,
  });
  return data;
}

export interface ChatThreadSummary {
  userId: string;
  userName: string;
  messageCount: number;
}

/**
 * Lists every member who has written in this room — used by the owner's
 * chat-review picker. Owner-only on the backend; non-owners get a 403.
 */
export async function fetchChatThreads(roomId: string) {
  const { data } = await api.get<ChatThreadSummary[]>(`/rooms/${roomId}/chat/threads`);
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

// ---------------------------------------------------------------------------
// AI metrics — counters, latency, recent queries observed since process start
// ---------------------------------------------------------------------------

export interface AiLatencyPercentiles {
  p50Ms: number;
  p95Ms: number;
  maxMs: number;
}

export interface AiRecentQuery {
  at: string; // ISO instant
  mode: "chat" | "agent";
  query: string;
  durationMs: number;
  failed: boolean;
}

export interface AiMetricsSnapshot {
  since: string; // ISO instant
  totalTurns: number;
  chatTurns: number;
  agentTurns: number;
  agentIterations: number;
  totalToolCalls: number;
  toolCallsByName: Record<string, number>;
  chatLatencyMs: AiLatencyPercentiles;
  agentLatencyMs: AiLatencyPercentiles;
  meanChatLatencyMs: number;
  meanAgentLatencyMs: number;
  recentQueries: AiRecentQuery[];
}

export async function fetchAiMetrics() {
  const { data } = await api.get<AiMetricsSnapshot>("/admin/ai-metrics");
  return data;
}

export async function resetAiMetrics() {
  await api.post("/admin/ai-metrics/reset");
}
