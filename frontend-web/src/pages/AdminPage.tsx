import * as Dialog from "@radix-ui/react-dialog";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import {
  ArrowLeft,
  ChevronUp,
  ChevronDown,
  Copy,
  DoorOpen,
  ShieldCheck,
  Trash2,
  Users as UsersIcon,
  X,
} from "lucide-react";
import { useMemo, useState } from "react";
import { Link, Navigate } from "react-router-dom";
import { Logo } from "@/components/brand/Logo";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  type AdminRoom,
  type AdminStats,
  type AdminUser,
  deleteAdminRoom,
  deleteAdminUser,
  fetchAdminRooms,
  fetchAdminStats,
  fetchAdminUsers,
  updateAdminUserRole,
  type UserRole,
} from "@/lib/api";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/auth-store";

type Tab = "users" | "rooms" | "stats";

export function AdminPage() {
  const me = useAuthStore((s) => s.user);
  const [tab, setTab] = useState<Tab>("users");

  if (!me) return <Navigate to="/login" replace />;
  if (me.role !== "ADMIN") return <Navigate to="/dashboard" replace />;

  return (
    <main className="min-h-screen bg-background text-zinc-100">
      <header className="border-b border-zinc-800 bg-background/95 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-4 lg:px-8">
          <div className="flex items-center gap-4">
            <Button asChild variant="ghost" className="px-2">
              <Link to="/dashboard" aria-label="Back to dashboard">
                <ArrowLeft className="h-4 w-4" />
              </Link>
            </Button>
            <Logo size={40} />
            <div>
              <p className="flex items-center gap-2 text-sm text-zinc-500">
                <ShieldCheck className="h-3.5 w-3.5 text-cyan" />
                Admin dashboard
              </p>
              <h1 className="text-xl font-semibold text-zinc-50">Codeleon · System</h1>
            </div>
          </div>
          <p className="text-xs text-zinc-500">
            Signed in as <span className="text-zinc-300">{me.email}</span>
          </p>
        </div>
        <nav className="mx-auto flex max-w-6xl gap-1 px-4 lg:px-8">
          <TabButton active={tab === "users"} onClick={() => setTab("users")}>
            <UsersIcon className="h-4 w-4" /> Users
          </TabButton>
          <TabButton active={tab === "rooms"} onClick={() => setTab("rooms")}>
            <DoorOpen className="h-4 w-4" /> Rooms
          </TabButton>
          <TabButton active={tab === "stats"} onClick={() => setTab("stats")}>
            <ChevronUp className="h-4 w-4" /> Stats
          </TabButton>
        </nav>
      </header>

      <section className="mx-auto max-w-6xl px-4 py-6 lg:px-8">
        {tab === "users" && <UsersTab meId={me.id} />}
        {tab === "rooms" && <RoomsTab />}
        {tab === "stats" && <StatsTab />}
      </section>
    </main>
  );
}

function TabButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        "flex items-center gap-2 border-b-2 px-3 py-2 text-sm font-medium transition",
        active
          ? "border-cyan text-zinc-50"
          : "border-transparent text-zinc-400 hover:text-zinc-200",
      )}
    >
      {children}
    </button>
  );
}

// ===========================================================================
// Users tab
// ===========================================================================

function UsersTab({ meId }: { meId: string }) {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<AdminUser | null>(null);

  const query = useQuery({ queryKey: ["admin", "users"], queryFn: fetchAdminUsers });

  const filtered = useMemo(() => {
    const list = query.data ?? [];
    const term = search.trim().toLowerCase();
    if (!term) return list;
    return list.filter(
      (u) =>
        u.email.toLowerCase().includes(term) ||
        u.fullName.toLowerCase().includes(term) ||
        u.id.toLowerCase().includes(term),
    );
  }, [query.data, search]);

  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-zinc-50">
            Users <span className="text-sm text-zinc-500">({query.data?.length ?? 0})</span>
          </h2>
          <p className="text-sm text-zinc-500">
            Promote, demote, or delete accounts. You cannot demote yourself or the last admin.
          </p>
        </div>
        <Input
          placeholder="Search by email, name, or id"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-sm"
        />
      </div>

      {query.isLoading && <p className="text-sm text-zinc-500">Loading users...</p>}
      {query.isError && (
        <p className="text-sm text-rose-400">Failed to load users.</p>
      )}

      <div className="overflow-x-auto rounded-lg border border-zinc-800">
        <table className="w-full text-sm">
          <thead className="bg-surface text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 text-left">Email</th>
              <th className="px-3 py-2 text-left">Name</th>
              <th className="px-3 py-2 text-left">Role</th>
              <th className="px-3 py-2 text-left">Auth</th>
              <th className="px-3 py-2 text-left">Joined</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-800">
            {filtered.map((u) => (
              <UserRow
                key={u.id}
                user={u}
                meId={meId}
                onOpen={() => setSelected(u)}
                onChanged={() => {
                  void queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
                  void queryClient.invalidateQueries({ queryKey: ["admin", "stats"] });
                }}
              />
            ))}
            {filtered.length === 0 && !query.isLoading && (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-sm text-zinc-500">
                  No users match.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {selected && (
        <UserDetailDialog
          user={selected}
          meId={meId}
          onClose={() => setSelected(null)}
          onChanged={() => {
            void queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
            void queryClient.invalidateQueries({ queryKey: ["admin", "stats"] });
            setSelected(null);
          }}
        />
      )}
    </div>
  );
}

function UserRow({
  user,
  meId,
  onOpen,
  onChanged,
}: {
  user: AdminUser;
  meId: string;
  onOpen: () => void;
  onChanged: () => void;
}) {
  const isMe = user.id === meId;

  const promoteMutation = useMutation({
    mutationFn: (newRole: UserRole) => updateAdminUserRole(user.id, newRole),
    onSuccess: () => onChanged(),
  });
  const deleteMutation = useMutation({
    mutationFn: () => deleteAdminUser(user.id),
    onSuccess: () => onChanged(),
  });

  const onToggleRole = () => {
    const next: UserRole = user.role === "ADMIN" ? "USER" : "ADMIN";
    if (
      next === "USER" &&
      !window.confirm(`Demote ${user.email} from ADMIN to USER?`)
    ) {
      return;
    }
    promoteMutation.mutate(next);
  };

  const onDelete = () => {
    if (!window.confirm(`Permanently delete ${user.email}? This cannot be undone.`)) {
      return;
    }
    deleteMutation.mutate();
  };

  return (
    <tr className="hover:bg-surface/40">
      <td className="px-3 py-2 font-mono text-xs">
        <button onClick={onOpen} className="text-zinc-200 hover:text-cyan">
          {user.email}
        </button>
        {isMe && <span className="ml-2 text-[10px] text-cyan">you</span>}
      </td>
      <td className="px-3 py-2 text-zinc-300">{user.fullName}</td>
      <td className="px-3 py-2">
        <RoleBadge role={user.role} />
      </td>
      <td className="px-3 py-2 text-xs text-zinc-400">
        {user.authMethod === "PASSWORD" ? "password" : user.authMethod.toLowerCase()}
      </td>
      <td className="px-3 py-2 text-xs text-zinc-500">
        {new Date(user.createdAt).toISOString().slice(0, 10)}
      </td>
      <td className="px-3 py-2 text-right">
        <div className="flex justify-end gap-1">
          <button
            type="button"
            disabled={isMe || promoteMutation.isPending}
            onClick={onToggleRole}
            className="inline-flex items-center gap-1 rounded border border-zinc-700 px-2 py-1 text-[11px] text-zinc-300 hover:bg-surfaceRaised disabled:opacity-40"
            title={user.role === "ADMIN" ? "Demote to USER" : "Promote to ADMIN"}
          >
            {user.role === "ADMIN" ? <ChevronDown className="h-3 w-3" /> : <ChevronUp className="h-3 w-3" />}
            {user.role === "ADMIN" ? "Demote" : "Promote"}
          </button>
          <button
            type="button"
            disabled={isMe || deleteMutation.isPending}
            onClick={onDelete}
            className="inline-flex items-center gap-1 rounded border border-rose-900 px-2 py-1 text-[11px] text-rose-300 hover:bg-rose-950/40 disabled:opacity-40"
          >
            <Trash2 className="h-3 w-3" />
            Delete
          </button>
        </div>
      </td>
    </tr>
  );
}

function UserDetailDialog({
  user,
  meId,
  onClose,
  onChanged,
}: {
  user: AdminUser;
  meId: string;
  onClose: () => void;
  onChanged: () => void;
}) {
  const isMe = user.id === meId;

  const promoteMutation = useMutation({
    mutationFn: (newRole: UserRole) => updateAdminUserRole(user.id, newRole),
    onSuccess: onChanged,
  });
  const deleteMutation = useMutation({
    mutationFn: () => deleteAdminUser(user.id),
    onSuccess: onChanged,
  });

  const errorMessage = (e: unknown) =>
    e instanceof AxiosError
      ? (e.response?.data as { message?: string } | undefined)?.message ?? e.message
      : "Action failed";

  return (
    <Dialog.Root open onOpenChange={(o) => { if (!o) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-black/70 backdrop-blur-sm" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 max-h-[90vh] w-[92vw] max-w-2xl -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-lg border border-zinc-800 bg-surface p-6 shadow-glow">
          <div className="flex items-start justify-between gap-4">
            <div>
              <Dialog.Title className="text-lg font-semibold text-zinc-50">
                {user.fullName}
                {isMe && <span className="ml-2 text-xs text-cyan">(you)</span>}
              </Dialog.Title>
              <Dialog.Description className="text-sm text-zinc-400">
                {user.email}
              </Dialog.Description>
            </div>
            <Dialog.Close asChild>
              <button className="rounded p-1 text-zinc-500 hover:bg-surfaceRaised hover:text-zinc-200" aria-label="Close">
                <X className="h-4 w-4" />
              </button>
            </Dialog.Close>
          </div>

          <dl className="mt-6 space-y-3 text-xs">
            <Field label="Internal ID (UUID)">
              <CopyableMono value={user.id} />
            </Field>
            <Field label="Email">
              <CopyableMono value={user.email} />
            </Field>
            <Field label="Full name">{user.fullName}</Field>
            <Field label="Avatar URL">
              {user.avatarUrl ? <CopyableMono value={user.avatarUrl} /> : <span className="text-zinc-600">—</span>}
            </Field>
            <Field label="Role"><RoleBadge role={user.role} /></Field>
          </dl>

          <h3 className="mt-6 text-xs uppercase tracking-wide text-zinc-500">Authentication</h3>
          <dl className="mt-2 space-y-3 text-xs">
            <Field label="Method">{user.authMethod === "PASSWORD" ? "Email + password" : `${user.authMethod} OAuth`}</Field>
            <Field label="Password">
              {user.authMethod === "PASSWORD"
                ? <span className="text-zinc-500">●●●●●●●● (bcrypt hash, not exposed)</span>
                : <span className="text-zinc-600">No local password (OAuth account)</span>}
            </Field>
            {user.oauthProvider && (
              <Field label="Provider"><span className="font-mono text-zinc-300">{user.oauthProvider}</span></Field>
            )}
            {user.oauthSubject && (
              <Field label="Provider subject"><CopyableMono value={user.oauthSubject} /></Field>
            )}
          </dl>

          <h3 className="mt-6 text-xs uppercase tracking-wide text-zinc-500">Activity</h3>
          <dl className="mt-2 space-y-3 text-xs">
            <Field label="Created at">{new Date(user.createdAt).toLocaleString()}</Field>
            <Field label="Updated at">{new Date(user.updatedAt).toLocaleString()}</Field>
            <Field label="Owned rooms">{user.ownedRoomsCount}</Field>
            <Field label="Member rooms">{user.memberRoomsCount}</Field>
          </dl>

          {(promoteMutation.isError || deleteMutation.isError) && (
            <p className="mt-4 rounded border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300">
              {errorMessage(promoteMutation.error ?? deleteMutation.error)}
            </p>
          )}

          <div className="mt-6 flex items-center justify-end gap-2">
            <Button
              variant="secondary"
              disabled={isMe || promoteMutation.isPending}
              onClick={() => promoteMutation.mutate(user.role === "ADMIN" ? "USER" : "ADMIN")}
            >
              {user.role === "ADMIN" ? <ChevronDown className="h-4 w-4" /> : <ChevronUp className="h-4 w-4" />}
              {user.role === "ADMIN" ? "Demote to USER" : "Promote to ADMIN"}
            </Button>
            <Button
              variant="secondary"
              disabled={isMe || deleteMutation.isPending}
              onClick={() => {
                if (window.confirm(`Permanently delete ${user.email}?`)) {
                  deleteMutation.mutate();
                }
              }}
              className="border-rose-900 text-rose-300 hover:bg-rose-950/40"
            >
              <Trash2 className="h-4 w-4" />
              Delete account
            </Button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-start gap-4">
      <dt className="w-32 shrink-0 text-zinc-500">{label}</dt>
      <dd className="flex-1 break-all text-zinc-300">{children}</dd>
    </div>
  );
}

function CopyableMono({ value }: { value: string }) {
  return (
    <button
      type="button"
      onClick={() => void navigator.clipboard?.writeText(value)}
      title="Copy"
      className="group inline-flex items-center gap-1 font-mono text-zinc-300 hover:text-zinc-50"
    >
      <span>{value}</span>
      <Copy className="h-3 w-3 opacity-0 group-hover:opacity-100" />
    </button>
  );
}

function RoleBadge({ role }: { role: UserRole }) {
  return (
    <span
      className={cn(
        "inline-block rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase tracking-wide",
        role === "ADMIN"
          ? "bg-cyan/20 text-cyan"
          : "bg-zinc-800 text-zinc-300",
      )}
    >
      {role}
    </span>
  );
}

// ===========================================================================
// Rooms tab
// ===========================================================================

function RoomsTab() {
  const queryClient = useQueryClient();
  const [search, setSearch] = useState("");
  const query = useQuery({ queryKey: ["admin", "rooms"], queryFn: fetchAdminRooms });

  const deleteMutation = useMutation({
    mutationFn: (roomId: string) => deleteAdminRoom(roomId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ["admin", "rooms"] });
      void queryClient.invalidateQueries({ queryKey: ["admin", "stats"] });
    },
  });

  const filtered = useMemo(() => {
    const list = query.data ?? [];
    const term = search.trim().toLowerCase();
    if (!term) return list;
    return list.filter(
      (r) =>
        r.name.toLowerCase().includes(term) ||
        (r.ownerEmail?.toLowerCase().includes(term) ?? false),
    );
  }, [query.data, search]);

  return (
    <div>
      <div className="mb-4 flex items-center justify-between gap-4">
        <div>
          <h2 className="text-lg font-semibold text-zinc-50">
            Rooms <span className="text-sm text-zinc-500">({query.data?.length ?? 0})</span>
          </h2>
          <p className="text-sm text-zinc-500">
            Force-delete inappropriate rooms. Cascades to members and files.
          </p>
        </div>
        <Input
          placeholder="Search by name or owner email"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="max-w-sm"
        />
      </div>

      <div className="overflow-x-auto rounded-lg border border-zinc-800">
        <table className="w-full text-sm">
          <thead className="bg-surface text-xs uppercase tracking-wide text-zinc-500">
            <tr>
              <th className="px-3 py-2 text-left">Name</th>
              <th className="px-3 py-2 text-left">Owner</th>
              <th className="px-3 py-2 text-left">Visibility</th>
              <th className="px-3 py-2 text-right">Members</th>
              <th className="px-3 py-2 text-right">Files</th>
              <th className="px-3 py-2 text-left">Created</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-zinc-800">
            {filtered.map((r) => (
              <RoomRow
                key={r.id}
                room={r}
                onDelete={() => {
                  if (window.confirm(`Permanently delete room "${r.name}"?`)) {
                    deleteMutation.mutate(r.id);
                  }
                }}
                disabled={deleteMutation.isPending}
              />
            ))}
            {filtered.length === 0 && !query.isLoading && (
              <tr>
                <td colSpan={7} className="px-3 py-6 text-center text-sm text-zinc-500">
                  No rooms match.
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function RoomRow({
  room,
  onDelete,
  disabled,
}: {
  room: AdminRoom;
  onDelete: () => void;
  disabled: boolean;
}) {
  return (
    <tr className="hover:bg-surface/40">
      <td className="px-3 py-2 text-zinc-200">{room.name}</td>
      <td className="px-3 py-2 font-mono text-xs text-zinc-400">
        {room.ownerEmail ?? "—"}
      </td>
      <td className="px-3 py-2">
        <span
          className={cn(
            "inline-block rounded px-1.5 py-0.5 text-[10px] font-semibold uppercase",
            room.visibility === "PUBLIC"
              ? "bg-emerald-900/40 text-emerald-300"
              : "bg-zinc-800 text-zinc-400",
          )}
        >
          {room.visibility}
        </span>
      </td>
      <td className="px-3 py-2 text-right text-zinc-300">{room.memberCount}</td>
      <td className="px-3 py-2 text-right text-zinc-300">{room.fileCount}</td>
      <td className="px-3 py-2 text-xs text-zinc-500">
        {new Date(room.createdAt).toISOString().slice(0, 10)}
      </td>
      <td className="px-3 py-2 text-right">
        <button
          type="button"
          onClick={onDelete}
          disabled={disabled}
          className="inline-flex items-center gap-1 rounded border border-rose-900 px-2 py-1 text-[11px] text-rose-300 hover:bg-rose-950/40 disabled:opacity-40"
        >
          <Trash2 className="h-3 w-3" />
          Delete
        </button>
      </td>
    </tr>
  );
}

// ===========================================================================
// Stats tab
// ===========================================================================

function StatsTab() {
  const query = useQuery({ queryKey: ["admin", "stats"], queryFn: fetchAdminStats, refetchInterval: 30_000 });

  if (query.isLoading) return <p className="text-sm text-zinc-500">Loading stats...</p>;
  if (query.isError || !query.data) return <p className="text-sm text-rose-400">Failed to load stats.</p>;

  const s: AdminStats = query.data;

  return (
    <div className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4">
        <Kpi label="Total users" value={s.totalUsers} sub={`+${s.usersJoinedLast7Days} last 7 days`} />
        <Kpi label="Total rooms" value={s.totalRooms} sub={`${s.roomsByVisibility.PUBLIC ?? 0} public · ${s.roomsByVisibility.PRIVATE ?? 0} private`} />
        <Kpi label="Total files" value={s.totalFiles} />
        <Kpi label="Memberships" value={s.totalMembers} />
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <Panel title="Users by role">
          <Distribution data={s.usersByRole} />
        </Panel>
        <Panel title="Users by auth method">
          <Distribution data={s.usersByAuthMethod} />
        </Panel>
      </div>

      <Panel title="RAG infrastructure">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-2xl font-semibold text-zinc-50">{s.totalRagChunks}</p>
            <p className="text-xs text-zinc-500">indexed chunks in Qdrant</p>
          </div>
          <span
            className={cn(
              "inline-block rounded px-2 py-1 text-[10px] font-semibold uppercase",
              s.ragInfrastructureUp
                ? "bg-emerald-900/40 text-emerald-300"
                : "bg-rose-900/40 text-rose-300",
            )}
          >
            {s.ragInfrastructureUp ? "● Up" : "● Down"}
          </span>
        </div>
      </Panel>
    </div>
  );
}

function Kpi({ label, value, sub }: { label: string; value: number; sub?: string }) {
  return (
    <div className="rounded-lg border border-zinc-800 bg-surface p-4">
      <p className="text-xs uppercase tracking-wide text-zinc-500">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-zinc-50">{value}</p>
      {sub && <p className="mt-1 text-xs text-zinc-500">{sub}</p>}
    </div>
  );
}

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-zinc-800 bg-surface p-4">
      <h3 className="mb-3 text-xs uppercase tracking-wide text-zinc-500">{title}</h3>
      {children}
    </div>
  );
}

function Distribution({ data }: { data: Record<string, number> }) {
  const total = Object.values(data).reduce((acc, v) => acc + v, 0);
  if (total === 0) {
    return <p className="text-sm text-zinc-500">No data yet.</p>;
  }
  return (
    <ul className="space-y-2">
      {Object.entries(data).map(([k, v]) => {
        const pct = total === 0 ? 0 : Math.round((v / total) * 100);
        return (
          <li key={k}>
            <div className="mb-1 flex items-center justify-between text-xs">
              <span className="font-mono text-zinc-300">{k}</span>
              <span className="text-zinc-500">{v} · {pct}%</span>
            </div>
            <div className="h-1.5 overflow-hidden rounded bg-zinc-800">
              <div className="h-full bg-cyan" style={{ width: `${pct}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
