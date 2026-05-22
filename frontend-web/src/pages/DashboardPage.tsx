import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { AnimatePresence, motion } from "framer-motion";
import { Archive, ArrowDownAZ, Check, Clock, DoorOpen, FileCode2, Github, Globe2, LayoutGrid, Link2, Lock, LogOut, Pin, Plus, Radio, Search, ShieldCheck, Sparkles, Users } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router-dom";
import { Logo } from "@/components/brand/Logo";
import { ActivityFeed } from "@/components/projects/ActivityFeed";
import { ProjectCard } from "@/components/projects/ProjectCard";
import { Button } from "@/components/ui/button";
import { FieldError } from "@/components/ui/field-error";
import { Input } from "@/components/ui/input";
import { MotionPage, fadeUp, stagger } from "@/components/ui/motion";
import {
  API_BASE_URL,
  createRoom,
  fetchCurrentUser,
  fetchMyRooms,
  fetchOAuthAccounts,
  fetchOAuthProviders,
  fetchPublicRooms,
  fetchTemplates,
  joinRoom,
  type OAuthAccount,
  type ProjectTemplate,
  type Room,
} from "@/lib/api";
import { cn } from "@/lib/utils";
import { CreateRoomValues, JoinRoomValues, createRoomSchema, joinRoomSchema } from "@/lib/validators";
import { useAuthStore } from "@/stores/auth-store";

type SortKey = "recent" | "alphabetical" | "files";
type FilterKey = "all" | "pinned" | "archived";

export function DashboardPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);
  const storedUser = useAuthStore((state) => state.user);
  const setUser = useAuthStore((state) => state.setUser);

  const [searchQuery, setSearchQuery] = useState("");
  const [searchFocused, setSearchFocused] = useState(false);
  const [activeSuggestionIndex, setActiveSuggestionIndex] = useState(-1);
  const [sortKey, setSortKey] = useState<SortKey>("recent");
  const [filterKey, setFilterKey] = useState<FilterKey>("all");

  const createForm = useForm<CreateRoomValues>({
    resolver: zodResolver(createRoomSchema),
    defaultValues: {
      name: "",
      description: "",
      visibility: "PRIVATE",
      templateId: "",
    },
  });
  const selectedTemplateId = createForm.watch("templateId");
  const joinForm = useForm<JoinRoomValues>({
    resolver: zodResolver(joinRoomSchema),
    defaultValues: {
      inviteCode: "",
    },
  });

  const { data: user } = useQuery({
    queryKey: ["me"],
    queryFn: fetchCurrentUser,
    initialData: storedUser ?? undefined,
  });

  useEffect(() => {
    if (user) {
      setUser(user);
    }
  }, [setUser, user]);

  // The "Archived" filter needs the server to include archived rooms,
  // which the default listing hides. We pass the includeArchived flag
  // and key the query on it so React Query keeps separate cache entries.
  const includeArchived = filterKey === "archived";
  const myRoomsQuery = useQuery({
    queryKey: ["rooms", "mine", { archived: includeArchived }],
    queryFn: () => fetchMyRooms(includeArchived),
  });

  const publicRoomsQuery = useQuery({
    queryKey: ["rooms", "public"],
    queryFn: fetchPublicRooms,
  });

  // Templates are shipped data, not user data, so we cache them aggressively
  // (1h staleTime). The dropdown rarely needs an authoritative refresh.
  const templatesQuery = useQuery({
    queryKey: ["templates"],
    queryFn: fetchTemplates,
    staleTime: 60 * 60 * 1000,
  });

  const createRoomMutation = useMutation({
    mutationFn: createRoom,
    onSuccess: () => {
      createForm.reset({ name: "", description: "", visibility: "PRIVATE", templateId: "" });
      void queryClient.invalidateQueries({ queryKey: ["rooms"] });
    },
  });

  const joinRoomMutation = useMutation({
    mutationFn: (values: JoinRoomValues) => joinRoom(values.inviteCode),
    onSuccess: () => {
      joinForm.reset({ inviteCode: "" });
      void queryClient.invalidateQueries({ queryKey: ["rooms"] });
    },
  });

  function handleLogout() {
    logout();
    navigate("/");
  }

  const myRooms = useMemo(() => myRoomsQuery.data ?? [], [myRoomsQuery.data]);
  const publicRooms = useMemo(() => publicRoomsQuery.data ?? [], [publicRoomsQuery.data]);

  const myRoomsView = useMemo(
    () => filterAndSort(myRooms, searchQuery, sortKey, filterKey),
    [myRooms, searchQuery, sortKey, filterKey],
  );
  // Public rooms ignore the pinned/archived filter — that filter is about
  // how the user organizes their *own* projects, not what they discover.
  const publicRoomsView = useMemo(
    () => filterAndSort(publicRooms, searchQuery, sortKey, "all"),
    [publicRooms, searchQuery, sortKey],
  );

  const totalFiles = useMemo(() => myRooms.reduce((acc, r) => acc + r.fileCount, 0), [myRooms]);
  const totalCollaborators = useMemo(
    () => myRooms.reduce((acc, r) => acc + Math.max(0, r.memberCount - 1), 0),
    [myRooms],
  );
  const searchSuggestions = useMemo(() => getProjectSuggestions(myRooms, searchQuery), [myRooms, searchQuery]);
  const showSearchSuggestions = searchFocused && searchQuery.trim().length > 0 && searchSuggestions.length > 0;

  useEffect(() => {
    setActiveSuggestionIndex(searchSuggestions.length > 0 ? 0 : -1);
  }, [searchQuery, searchSuggestions.length]);

  function openProjectSuggestion(room: Room) {
    setSearchFocused(false);
    navigate(`/rooms/${room.id}`);
  }

  function handleSearchKeyDown(event: React.KeyboardEvent<HTMLInputElement>) {
    if (searchSuggestions.length === 0) {
      return;
    }

    if (event.key === "ArrowDown") {
      event.preventDefault();
      setSearchFocused(true);
      setActiveSuggestionIndex((current) => (current + 1) % searchSuggestions.length);
      return;
    }

    if (event.key === "ArrowUp") {
      event.preventDefault();
      setSearchFocused(true);
      setActiveSuggestionIndex((current) => (current <= 0 ? searchSuggestions.length - 1 : current - 1));
      return;
    }

    if (event.key === "Enter" && showSearchSuggestions && activeSuggestionIndex >= 0) {
      event.preventDefault();
      openProjectSuggestion(searchSuggestions[activeSuggestionIndex]);
      return;
    }

    if (event.key === "Escape") {
      setSearchFocused(false);
    }
  }

  return (
    <main className="min-h-screen bg-background">
      <aside className="fixed left-0 top-0 hidden h-screen w-64 border-r border-zinc-800 bg-surface/80 p-4 lg:block">
        <Link to="/" className="flex items-center gap-3 px-2 py-2">
          <Logo size={40} />
          <span className="font-semibold text-zinc-50">Codeleon</span>
        </Link>
        <nav className="mt-8 space-y-1 text-sm text-zinc-400">
          <a className="flex items-center gap-3 rounded-md bg-surfaceRaised px-3 py-2 text-zinc-100" href="#projects">
            <Users className="h-4 w-4" />
            My projects
          </a>
          <a className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-surfaceRaised hover:text-zinc-100" href="#public">
            <Radio className="h-4 w-4" />
            Public projects
          </a>
          <a className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-surfaceRaised hover:text-zinc-100" href="#integrations">
            <Link2 className="h-4 w-4" />
            Integrations
          </a>
        </nav>
      </aside>

      <MotionPage className="lg:pl-64">
        <header className="flex items-center justify-between border-b border-zinc-800 bg-background/90 px-4 py-4 backdrop-blur lg:px-8">
          <div>
            <p className="text-sm text-zinc-500">Dashboard</p>
            <h1 className="text-xl font-semibold text-zinc-50">Welcome, {user?.fullName ?? "builder"}</h1>
          </div>
          <Button variant="secondary" onClick={handleLogout}>
            <LogOut className="h-4 w-4" />
            Logout
          </Button>
        </header>

        <div className="mx-auto w-full max-w-7xl px-4 py-8 lg:px-8">
          <div className="grid gap-8 xl:grid-cols-[minmax(0,1fr)_320px]">
            <div className="min-w-0 space-y-8">
          <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-3 sm:grid-cols-3">
            <StatTile icon={<FileCode2 className="h-4 w-4 text-cyan" />} label="Projects" value={myRooms.length} />
            <StatTile icon={<FileCode2 className="h-4 w-4 text-cyan" />} label="Files across projects" value={totalFiles} />
            <StatTile icon={<Users className="h-4 w-4 text-cyan" />} label="Collaborators" value={totalCollaborators} />
          </motion.div>

          <section id="projects" className="scroll-mt-8 space-y-4">
            <div className="flex flex-wrap items-center justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-zinc-50">My projects</h2>
                <p className="text-sm text-zinc-500">Your collaborative workspaces.</p>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-zinc-500" />
                  <Input
                    aria-label="Search projects"
                    aria-autocomplete="list"
                    aria-controls="my-project-search-suggestions"
                    aria-expanded={showSearchSuggestions}
                    className="h-10 w-72 pl-9"
                    placeholder="Search projects..."
                    role="combobox"
                    value={searchQuery}
                    onBlur={() => window.setTimeout(() => setSearchFocused(false), 120)}
                    onChange={(e) => {
                      setSearchQuery(e.target.value);
                      setSearchFocused(true);
                    }}
                    onFocus={() => setSearchFocused(true)}
                    onKeyDown={handleSearchKeyDown}
                  />
                  <AnimatePresence>
                    {showSearchSuggestions && (
                      <motion.div
                        id="my-project-search-suggestions"
                        initial={{ opacity: 0, y: -6, scale: 0.98 }}
                        animate={{ opacity: 1, y: 0, scale: 1 }}
                        exit={{ opacity: 0, y: -4, scale: 0.98 }}
                        transition={{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }}
                        className="absolute left-0 top-12 z-40 w-80 overflow-hidden rounded-lg border border-zinc-800 bg-zinc-950/95 shadow-[0_24px_70px_rgba(0,0,0,0.46)] backdrop-blur"
                        role="listbox"
                      >
                        <div className="border-b border-zinc-800 px-3 py-2 text-[11px] uppercase tracking-wide text-zinc-500">
                          My project suggestions
                        </div>
                        <div className="max-h-80 overflow-y-auto p-1.5">
                          {searchSuggestions.map((room, index) => (
                            <SearchSuggestion
                              key={room.id}
                              active={index === activeSuggestionIndex}
                              query={searchQuery}
                              room={room}
                              onMouseEnter={() => setActiveSuggestionIndex(index)}
                              onSelect={() => openProjectSuggestion(room)}
                            />
                          ))}
                        </div>
                      </motion.div>
                    )}
                  </AnimatePresence>
                </div>
                <FilterMenu filterKey={filterKey} onChange={setFilterKey} />
                <SortMenu sortKey={sortKey} onChange={setSortKey} />
              </div>
            </div>

            <div className="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
              <motion.form
                variants={fadeUp}
                initial="hidden"
                animate="show"
                className="relative space-y-4 overflow-hidden rounded-lg border border-zinc-800 bg-surface p-5 shadow-[0_16px_42px_rgba(0,0,0,0.22)]"
                onSubmit={createForm.handleSubmit((values) =>
                  createRoomMutation.mutate({
                    ...values,
                    description: values.description?.trim() || undefined,
                    templateId: values.templateId?.trim() || undefined,
                  }),
                )}
              >
                <span className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-cyan/60 to-transparent" />
                <div className="flex items-center gap-2">
                  <Plus className="h-4 w-4 text-cyan" />
                  <h3 className="font-medium text-zinc-100">Create project</h3>
                </div>

                <div className="space-y-2">
                  <label className="text-sm font-medium text-zinc-200" htmlFor="room-name">
                    Name
                  </label>
                  <Input id="room-name" placeholder="Spring Boot Study Room" {...createForm.register("name")} />
                  <FieldError message={createForm.formState.errors.name?.message} />
                </div>

                <div className="space-y-2">
                  <label className="text-sm font-medium text-zinc-200" htmlFor="room-description">
                    Description
                  </label>
                  <Input id="room-description" placeholder="Optional short context" {...createForm.register("description")} />
                  <FieldError message={createForm.formState.errors.description?.message} />
                </div>

                <TemplatePicker
                  templates={templatesQuery.data ?? []}
                  isLoading={templatesQuery.isLoading}
                  selectedId={selectedTemplateId ?? ""}
                  onChange={(id) => createForm.setValue("templateId", id, { shouldDirty: true })}
                />

                <div className="grid grid-cols-2 gap-2">
                  <label className="flex cursor-pointer items-center gap-3 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3 text-sm text-zinc-300">
                    <input className="accent-signature" type="radio" value="PRIVATE" {...createForm.register("visibility")} />
                    <Lock className="h-4 w-4 text-zinc-500" />
                    Private
                  </label>
                  <label className="flex cursor-pointer items-center gap-3 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3 text-sm text-zinc-300">
                    <input className="accent-signature" type="radio" value="PUBLIC" {...createForm.register("visibility")} />
                    <Globe2 className="h-4 w-4 text-zinc-500" />
                    Public
                  </label>
                </div>

                {createRoomMutation.isError && <p className="text-sm text-danger">{getErrorMessage(createRoomMutation.error)}</p>}

                <Button disabled={createRoomMutation.isPending} type="submit">
                  <Plus className="h-4 w-4" />
                  {createRoomMutation.isPending ? "Creating..." : "Create project"}
                </Button>
              </motion.form>

              <motion.form
                variants={fadeUp}
                initial="hidden"
                animate="show"
                className="relative space-y-4 overflow-hidden rounded-lg border border-zinc-800 bg-surface p-5 shadow-[0_16px_42px_rgba(0,0,0,0.22)]"
                onSubmit={joinForm.handleSubmit((values) => joinRoomMutation.mutate(values))}
              >
                <span className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-violet/60 to-transparent" />
                <div className="flex items-center gap-2">
                  <DoorOpen className="h-4 w-4 text-cyan" />
                  <h3 className="font-medium text-zinc-100">Join with invite</h3>
                </div>
                <p className="text-sm leading-6 text-zinc-500">Paste an invite code to join a private or public workspace.</p>
                <div className="space-y-2">
                  <label className="text-sm font-medium text-zinc-200" htmlFor="invite-code">
                    Invite code
                  </label>
                  <Input id="invite-code" placeholder="Paste invite code" {...joinForm.register("inviteCode")} />
                  <FieldError message={joinForm.formState.errors.inviteCode?.message} />
                </div>

                {joinRoomMutation.isError && <p className="text-sm text-danger">{getErrorMessage(joinRoomMutation.error)}</p>}

                <Button disabled={joinRoomMutation.isPending} type="submit" variant="secondary">
                  <DoorOpen className="h-4 w-4" />
                  {joinRoomMutation.isPending ? "Joining..." : "Join project"}
                </Button>
              </motion.form>
            </div>

            <ProjectGrid
              emptyText={searchQuery ? "No projects match your search" : "No projects yet"}
              isLoading={myRoomsQuery.isLoading}
              rooms={myRoomsView}
            />
          </section>

          <section id="public" className="scroll-mt-8 space-y-4">
            <div>
              <h2 className="text-lg font-semibold text-zinc-50">Public projects</h2>
              <p className="text-sm text-zinc-500">Discover public programming sessions.</p>
            </div>
            <ProjectGrid
              emptyText={searchQuery ? "No public projects match your search" : "No public projects yet"}
              isLoading={publicRoomsQuery.isLoading}
              rooms={publicRoomsView}
            />
          </section>

          <section id="integrations" className="scroll-mt-8 space-y-4">
            <div>
              <h2 className="text-lg font-semibold text-zinc-50">Integrations</h2>
              <p className="text-sm text-zinc-500">Connect external accounts for imports and project workflows.</p>
            </div>
            <AccountIntegrations />
          </section>
            </div>

            <aside className="hidden xl:block">
              <div className="sticky top-8 space-y-4">
                <ActivityFeed currentUserId={user?.id} />
              </div>
            </aside>
          </div>
        </div>
      </MotionPage>
    </main>
  );
}

function filterAndSort(rooms: Room[], query: string, sortKey: SortKey, filterKey: FilterKey): Room[] {
  let filtered = rooms;

  switch (filterKey) {
    case "pinned":
      filtered = filtered.filter((r) => r.pinned);
      break;
    case "archived":
      filtered = filtered.filter((r) => r.archived);
      break;
    case "all":
    default:
      // Backend already excludes archived from the default listing, so
      // there is nothing more to filter here.
      break;
  }

  const trimmed = query.trim().toLowerCase();
  if (trimmed) {
    filtered = filtered.filter((room) => roomSearchHaystack(room).includes(trimmed));
  }

  const sorted = [...filtered];
  switch (sortKey) {
    case "alphabetical":
      sorted.sort((a, b) => a.name.localeCompare(b.name));
      break;
    case "files":
      sorted.sort((a, b) => b.fileCount - a.fileCount);
      break;
    case "recent":
    default:
      sorted.sort((a, b) => new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime());
      break;
  }
  // Pinned rooms always come first within whatever sort order is selected,
  // so the user's most-used projects stay at the top of the grid.
  if (filterKey !== "pinned") {
    sorted.sort((a, b) => Number(b.pinned) - Number(a.pinned));
  }
  return sorted;
}

function roomSearchHaystack(room: Room) {
  return [
    room.name,
    room.description,
    room.ownerName,
    room.lastEditedByName,
    room.visibility,
    room.inviteCode,
  ]
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
}

function getProjectSuggestions(rooms: Room[], query: string) {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) {
    return [];
  }

  return [...rooms]
    .filter((room) => roomSearchHaystack(room).includes(trimmed))
    .sort((a, b) => {
      const aNameStarts = a.name.toLowerCase().startsWith(trimmed);
      const bNameStarts = b.name.toLowerCase().startsWith(trimmed);
      if (aNameStarts !== bNameStarts) {
        return Number(bNameStarts) - Number(aNameStarts);
      }
      if (a.pinned !== b.pinned) {
        return Number(b.pinned) - Number(a.pinned);
      }
      return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime();
    })
    .slice(0, 6);
}

function SearchSuggestion({
  active,
  onMouseEnter,
  onSelect,
  query,
  room,
}: {
  active: boolean;
  onMouseEnter: () => void;
  onSelect: () => void;
  query: string;
  room: Room;
}) {
  const updatedAt = new Date(room.updatedAt).toLocaleDateString(undefined, { month: "short", day: "numeric" });
  const details = [
    `${room.fileCount} ${room.fileCount === 1 ? "file" : "files"}`,
    `${room.memberCount} ${room.memberCount === 1 ? "member" : "members"}`,
    `Updated ${updatedAt}`,
  ];

  return (
    <button
      type="button"
      role="option"
      aria-selected={active}
      onMouseDown={(event) => event.preventDefault()}
      onMouseEnter={onMouseEnter}
      onClick={onSelect}
      className={cn(
        "group flex w-full items-start gap-3 rounded-md px-3 py-2.5 text-left transition",
        active ? "bg-surfaceRaised text-zinc-50" : "text-zinc-300 hover:bg-surfaceRaised hover:text-zinc-50",
      )}
    >
      <span
        className={cn(
          "mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-md border transition",
          active ? "border-cyan/50 bg-cyan/10 text-cyan" : "border-zinc-800 bg-zinc-950 text-zinc-500 group-hover:text-cyan",
        )}
      >
        {room.visibility === "PUBLIC" ? <Globe2 className="h-4 w-4" /> : <Lock className="h-4 w-4" />}
      </span>
      <span className="min-w-0 flex-1">
        <span className="flex items-center gap-2">
          <span className="truncate text-sm font-medium">{highlightSearchText(room.name, query)}</span>
          {room.pinned && <Pin className="h-3.5 w-3.5 shrink-0 text-cyan" />}
          {room.archived && <Archive className="h-3.5 w-3.5 shrink-0 text-zinc-500" />}
        </span>
        <span className="mt-1 block truncate text-xs text-zinc-500">
          {room.description ? highlightSearchText(room.description, query) : `Owned by ${room.ownerName}`}
        </span>
        <span className="mt-1.5 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11px] text-zinc-600">
          {details.map((detail) => (
            <span key={detail}>{detail}</span>
          ))}
        </span>
      </span>
    </button>
  );
}

function highlightSearchText(text: string, query: string) {
  const trimmed = query.trim();
  if (!trimmed) {
    return text;
  }

  const start = text.toLowerCase().indexOf(trimmed.toLowerCase());
  if (start < 0) {
    return text;
  }

  const end = start + trimmed.length;
  return (
    <>
      {text.slice(0, start)}
      <mark className="rounded-sm bg-cyan/15 px-0.5 text-cyan">{text.slice(start, end)}</mark>
      {text.slice(end)}
    </>
  );
}

function StatTile({ icon, label, value }: { icon: React.ReactNode; label: string; value: number }) {
  return (
    <motion.div
      variants={fadeUp}
      whileHover={{ y: -3, scale: 1.01 }}
      transition={{ type: "spring", stiffness: 420, damping: 32 }}
      className="rounded-lg border border-zinc-800 bg-surface px-5 py-4 shadow-[0_12px_34px_rgba(0,0,0,0.2)] transition hover:border-cyan/40 hover:bg-surfaceRaised"
    >
      <div className="flex items-center gap-2 text-xs uppercase tracking-wide text-zinc-500">
        {icon}
        {label}
      </div>
      <p className="mt-2 text-2xl font-semibold text-zinc-50">{value}</p>
    </motion.div>
  );
}

function AccountIntegrations({ className }: { className?: string }) {
  const accountsQuery = useQuery({
    queryKey: ["oauth-accounts"],
    queryFn: fetchOAuthAccounts,
    staleTime: 30_000,
  });
  const providersQuery = useQuery({
    queryKey: ["auth-providers"],
    queryFn: fetchOAuthProviders,
    staleTime: 60_000,
  });

  const accounts = accountsQuery.data ?? [];
  const providers = providersQuery.data?.providers ?? [];
  const github = accounts.find((account) => account.provider === "github");
  const githubAvailable = providers.includes("github");

  return (
    <motion.section
      variants={fadeUp}
      initial="hidden"
      animate="show"
      className={cn("relative overflow-hidden rounded-lg border border-zinc-800 bg-surface p-5 shadow-[0_16px_42px_rgba(0,0,0,0.22)]", className)}
    >
      <span className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-cyan/60 to-transparent" />
      <div className="flex items-start gap-3">
        <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-zinc-800 bg-zinc-950">
          <ShieldCheck className="h-4 w-4 text-cyan" />
        </div>
        <div className="min-w-0">
          <h2 className="text-sm font-semibold text-zinc-50">Account integrations</h2>
          <p className="mt-1 text-xs leading-5 text-zinc-500">
            Link GitHub to import private repositories from the same Codeleon account.
          </p>
        </div>
      </div>

      <div className="mt-4 space-y-3">
        <IntegrationRow
          account={github}
          available={githubAvailable}
          icon={<Github className="h-4 w-4" />}
          provider="github"
          title="GitHub"
        />
      </div>

      {accountsQuery.isError && (
        <p className="mt-3 text-xs text-rose-400">Could not load linked accounts.</p>
      )}
    </motion.section>
  );
}

function IntegrationRow({
  account,
  available,
  icon,
  provider,
  title,
}: {
  account: OAuthAccount | undefined;
  available: boolean;
  icon: React.ReactNode;
  provider: string;
  title: string;
}) {
  const connected = Boolean(account);
  return (
    <div className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <span className="text-zinc-400">{icon}</span>
          <div className="min-w-0">
            <div className="flex items-center gap-2">
              <span className="text-sm font-medium text-zinc-100">{title}</span>
              {connected && (
                <span className="inline-flex items-center gap-1 rounded-full border border-emerald-800 bg-emerald-950/40 px-1.5 py-0.5 text-[10px] text-emerald-300">
                  <Check className="h-3 w-3" />
                  Connected
                </span>
              )}
            </div>
            <p className="truncate text-xs text-zinc-500">
              {account?.email ?? (available ? "Not connected" : "OAuth provider not configured")}
            </p>
          </div>
        </div>

        {!connected && available && (
          <Button asChild variant="secondary" className="h-8 shrink-0 px-3 text-xs">
            <a href={`${API_BASE_URL}/oauth2/authorization/${provider}`}>
              <Link2 className="h-3.5 w-3.5" />
              Connect
            </a>
          </Button>
        )}
      </div>
      {connected && account?.scopes && (
        <p className="mt-2 truncate font-mono text-[10px] text-zinc-600">Scopes: {account.scopes}</p>
      )}
    </div>
  );
}

function FilterMenu({ filterKey, onChange }: { filterKey: FilterKey; onChange: (key: FilterKey) => void }) {
  const options: { key: FilterKey; label: string; icon: React.ReactNode }[] = [
    { key: "all", label: "All", icon: <LayoutGrid className="h-3.5 w-3.5" /> },
    { key: "pinned", label: "Pinned", icon: <Pin className="h-3.5 w-3.5" /> },
    { key: "archived", label: "Archived", icon: <Archive className="h-3.5 w-3.5" /> },
  ];
  return (
    <div className="flex items-center gap-1 rounded-md border border-zinc-800 bg-surface p-1">
      {options.map((opt) => (
        <button
          key={opt.key}
          type="button"
          onClick={() => onChange(opt.key)}
          className={
            opt.key === filterKey
              ? "inline-flex items-center gap-1.5 rounded-sm bg-surfaceRaised px-2.5 py-1 text-xs font-medium text-zinc-100"
              : "inline-flex items-center gap-1.5 rounded-sm px-2.5 py-1 text-xs text-zinc-400 transition hover:bg-surfaceRaised hover:text-zinc-200"
          }
          aria-pressed={opt.key === filterKey}
        >
          {opt.icon}
          {opt.label}
        </button>
      ))}
    </div>
  );
}

function SortMenu({ sortKey, onChange }: { sortKey: SortKey; onChange: (key: SortKey) => void }) {
  const options: { key: SortKey; label: string; icon: React.ReactNode }[] = [
    { key: "recent", label: "Recent", icon: <Clock className="h-3.5 w-3.5" /> },
    { key: "alphabetical", label: "A-Z", icon: <ArrowDownAZ className="h-3.5 w-3.5" /> },
    { key: "files", label: "Most files", icon: <FileCode2 className="h-3.5 w-3.5" /> },
  ];
  return (
    <div className="flex items-center gap-1 rounded-md border border-zinc-800 bg-surface p-1">
      {options.map((opt) => (
        <button
          key={opt.key}
          type="button"
          onClick={() => onChange(opt.key)}
          className={
            opt.key === sortKey
              ? "inline-flex items-center gap-1.5 rounded-sm bg-surfaceRaised px-2.5 py-1 text-xs font-medium text-zinc-100"
              : "inline-flex items-center gap-1.5 rounded-sm px-2.5 py-1 text-xs text-zinc-400 transition hover:bg-surfaceRaised hover:text-zinc-200"
          }
          aria-pressed={opt.key === sortKey}
        >
          {opt.icon}
          {opt.label}
        </button>
      ))}
    </div>
  );
}

function ProjectGrid({ emptyText, isLoading, rooms }: { emptyText: string; isLoading: boolean; rooms: Room[] }) {
  if (isLoading) {
    return (
      <div className="rounded-lg border border-zinc-800 bg-surface/50 p-8">
        <div className="mx-auto max-w-2xl space-y-3">
          {[0, 1, 2].map((i) => (
            <div key={i} className="codeleon-shimmer h-12 rounded-md bg-zinc-900" />
          ))}
        </div>
      </div>
    );
  }
  if (rooms.length === 0) {
    return (
      <motion.div initial="hidden" animate="show" variants={fadeUp} className="rounded-lg border border-dashed border-zinc-800 bg-surface/50 p-8 text-center">
        <p className="font-medium text-zinc-200">{emptyText}</p>
        <p className="mt-2 text-sm text-zinc-500">Create or join a project to start collaborating.</p>
      </motion.div>
    );
  }
  return (
    <motion.div variants={stagger} initial="hidden" animate="show" className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      {rooms.map((room) => (
        <motion.div key={room.id} variants={fadeUp}>
          <ProjectCard room={room} />
        </motion.div>
      ))}
    </motion.div>
  );
}

/**
 * Horizontal list of template cards shown inside the Create-project form.
 * Picking a card sets the form's templateId; picking "Empty" clears it so
 * the room is created with the legacy default-file behaviour. Kept inside
 * the form (rather than as a separate dropdown menu) so users land on a
 * populated workspace without a second click.
 */
function TemplatePicker({
  templates,
  isLoading,
  selectedId,
  onChange,
}: {
  templates: ProjectTemplate[];
  isLoading: boolean;
  selectedId: string;
  onChange: (id: string) => void;
}) {
  return (
    <div className="space-y-2">
      <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
        <Sparkles className="h-4 w-4 text-cyan" />
        Start from a template
        <span className="text-xs font-normal text-zinc-500">(optional)</span>
      </div>
      <div className="flex gap-2 overflow-x-auto pb-1">
        <TemplateChip
          selected={!selectedId}
          onClick={() => onChange("")}
          title="Empty"
          subtitle="Single blank file"
        />
        {isLoading && (
          <div className="flex h-[58px] w-40 shrink-0 animate-pulse rounded-md border border-zinc-800 bg-zinc-900" />
        )}
        {templates.map((t) => (
          <TemplateChip
            key={t.id}
            selected={selectedId === t.id}
            onClick={() => onChange(t.id)}
            title={t.name}
            subtitle={`${t.language} · ${t.fileCount} ${t.fileCount === 1 ? "file" : "files"}`}
            tooltip={t.description}
          />
        ))}
      </div>
    </div>
  );
}

function TemplateChip({
  selected,
  onClick,
  title,
  subtitle,
  tooltip,
}: {
  selected: boolean;
  onClick: () => void;
  title: string;
  subtitle: string;
  tooltip?: string;
}) {
  return (
    <motion.button
      type="button"
      whileHover={{ y: -2 }}
      whileTap={{ scale: 0.98 }}
      onClick={onClick}
      title={tooltip}
      className={
        selected
          ? "flex w-40 shrink-0 flex-col items-start gap-0.5 rounded-md border border-signature bg-signature/10 px-3 py-2 text-left ring-2 ring-signature/30"
          : "flex w-40 shrink-0 flex-col items-start gap-0.5 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 text-left transition hover:border-zinc-600 hover:bg-zinc-900"
      }
      aria-pressed={selected}
    >
      <span className={selected ? "text-sm font-medium text-zinc-50" : "text-sm font-medium text-zinc-200"}>{title}</span>
      <span className="text-xs text-zinc-500">{subtitle}</span>
    </motion.button>
  );
}

function getErrorMessage(error: unknown) {
  if (error instanceof AxiosError) {
    return error.response?.data?.message ?? "Request failed";
  }
  return "Request failed";
}
