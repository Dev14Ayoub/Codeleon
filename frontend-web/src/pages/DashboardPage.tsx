import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { AnimatePresence, motion } from "framer-motion";
import { Activity, Archive, ArrowDownAZ, Check, ChevronDown, Clock, Database, DoorOpen, FileCode2, Github, Globe2, LayoutGrid, Link2, Loader2, Lock, LogOut, Menu, PanelRightClose, PanelRightOpen, Pin, Plus, Radio, Search, ShieldCheck, Sparkles, Terminal, Upload, Users, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import * as Y from "yjs";
import { AnimatedBackdrop } from "@/components/brand/AnimatedBackdrop";
import { Logo } from "@/components/brand/Logo";
import { ActivityFeed } from "@/components/projects/ActivityFeed";
import { ProjectCard } from "@/components/projects/ProjectCard";
import { Button } from "@/components/ui/button";
import { FieldError } from "@/components/ui/field-error";
import { Input } from "@/components/ui/input";
import { MotionPage, fadeUp, stagger } from "@/components/ui/motion";
import {
  API_BASE_URL,
  createRoomFile,
  createRoom,
  fetchGithubRepositories,
  fetchCurrentUser,
  fetchTemplate,
  fetchMyRooms,
  fetchOAuthAccounts,
  fetchOAuthProviders,
  fetchPublicRooms,
  fetchTemplates,
  importGithub,
  joinRoom,
  saveRoomSnapshot,
  type GithubImportResponse,
  type GithubRepository,
  type OAuthAccount,
  type ProjectTemplate,
  type Room,
} from "@/lib/api";
import { prepareLocalImport, type ImportFilterReport, type PreparedFile } from "@/lib/files/local-import";
import { cn } from "@/lib/utils";
import { CreateRoomValues, JoinRoomValues, createRoomSchema, joinRoomSchema } from "@/lib/validators";
import { useAuthStore } from "@/stores/auth-store";

type SortKey = "recent" | "alphabetical" | "files";
type FilterKey = "all" | "pinned" | "archived";
type CreateProjectSource = "blank" | "local" | "github";

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
  const [createSource, setCreateSource] = useState<CreateProjectSource>("blank");
  // Activity panel visibility persists in localStorage so the user's
  // preference survives reloads. Default to visible — first-time visitors
  // see the feature, returning users keep their choice.
  const [activityOpen, setActivityOpen] = useState<boolean>(() => {
    if (typeof window === "undefined") return true;
    return window.localStorage.getItem("codeleon-activity-panel") !== "closed";
  });
  useEffect(() => {
    if (typeof window === "undefined") return;
    window.localStorage.setItem("codeleon-activity-panel", activityOpen ? "open" : "closed");
  }, [activityOpen]);

  // Mobile / tablet navigation drawer. The sidebar is hidden below lg
  // (1024px) so we expose a hamburger that slides it in over the page.
  // Closes on backdrop click, on link click, and when the viewport
  // crosses into lg (handled by the user / browser resize).
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const closeMobileNav = () => setMobileNavOpen(false);
  const [localImportReport, setLocalImportReport] = useState<ImportFilterReport | null>(null);
  const [localImportName, setLocalImportName] = useState<string | null>(null);
  const [githubRepoSearch, setGithubRepoSearch] = useState("");
  const [githubRepoUrl, setGithubRepoUrl] = useState("");
  const [githubBranch, setGithubBranch] = useState("");
  const localImportInputRef = useRef<HTMLInputElement | null>(null);

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

  const githubRepositoriesQuery = useQuery({
    queryKey: ["github-repositories"],
    queryFn: fetchGithubRepositories,
    enabled: createSource === "github",
    retry: false,
    staleTime: 60_000,
  });

  const createRoomMutation = useMutation({
    mutationFn: async (values: CreateRoomValues) => {
      if (createSource === "local" && (!localImportReport || localImportReport.prepared.length === 0)) {
        throw new Error("Choose a folder with at least one importable text file.");
      }
      if (createSource === "github" && !githubRepoUrl.trim()) {
        throw new Error("Choose or enter a GitHub repository.");
      }

      const room = await createRoom({
        ...values,
        description: values.description?.trim() || undefined,
        templateId: createSource === "blank" ? values.templateId?.trim() || undefined : undefined,
      });

      if (createSource === "blank" && values.templateId?.trim()) {
        await seedTemplateFiles(room.id, values.templateId.trim());
      }

      if (createSource === "local") {
        await materializeLocalImport(room.id, localImportReport!.prepared);
      }

      if (createSource === "github") {
        const repoUrl = githubRepoUrl.trim();
        const response = await importGithub(room.id, {
          repoUrl,
          branch: githubBranch.trim() || undefined,
        });
        await seedImportedFiles(room.id, response.imported);
      }

      return room;
    },
    onSuccess: (room) => {
      createForm.reset({ name: "", description: "", visibility: "PRIVATE", templateId: "" });
      setCreateSource("blank");
      setLocalImportReport(null);
      setLocalImportName(null);
      setGithubRepoSearch("");
      setGithubRepoUrl("");
      setGithubBranch("");
      void queryClient.invalidateQueries({ queryKey: ["rooms"] });
      navigate(`/rooms/${room.id}`);
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

  async function handleDashboardLocalFiles(fileList: FileList | null) {
    if (!fileList || fileList.length === 0) return;
    const report = await prepareLocalImport(fileList);
    setLocalImportReport(report);
    const projectName = inferLocalProjectName(fileList);
    setLocalImportName(projectName);
    setCreateSource("local");
    createForm.setValue("templateId", "", { shouldDirty: true });
    if (!createForm.getValues("name").trim() && projectName) {
      createForm.setValue("name", projectName, { shouldDirty: true, shouldValidate: true });
    }
  }

  async function materializeLocalImport(roomId: string, files: PreparedFile[]) {
    for (const file of files) {
      await createRoomFile(roomId, file.path);
    }
    await saveRoomSnapshot(roomId, encodeFilesSnapshot(files));
  }

  async function seedImportedFiles(roomId: string, files: GithubImportResponse["imported"]) {
    if (files.length === 0) {
      return;
    }
    await saveRoomSnapshot(roomId, encodeFilesSnapshot(files.map((file) => ({
      path: file.path,
      content: file.content,
    }))));
  }

  async function seedTemplateFiles(roomId: string, templateId: string) {
    const template = await fetchTemplate(templateId);
    const files = template.files
      .filter((file) => file.content !== undefined && file.content !== null)
      .map((file) => ({
        path: file.path,
        content: file.content ?? "",
      }));
    if (files.length > 0) {
      await saveRoomSnapshot(roomId, encodeFilesSnapshot(files));
    }
  }

  const myRooms = useMemo(() => myRoomsQuery.data ?? [], [myRoomsQuery.data]);
  const publicRooms = useMemo(() => publicRoomsQuery.data ?? [], [publicRoomsQuery.data]);
  const projectTemplates = templatesQuery.data ?? [];
  const selectedTemplate = projectTemplates.find((template) => template.id === selectedTemplateId) ?? null;
  const githubRepositories = githubRepositoriesQuery.data ?? [];
  const filteredGithubRepositories = useMemo(
    () => filterGithubRepositories(githubRepositories, githubRepoSearch),
    [githubRepositories, githubRepoSearch],
  );

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
    // overflow-x-hidden defends against the occasional horizontal scrollbar
    // showing up when a long, unbreakable string (room name, GitHub URL,
    // invite code) escapes its container. The legitimate scroll direction
    // for the page is always vertical.
    <main className="relative min-h-screen overflow-x-hidden bg-background">
      {/* Subtle variant — dashboard is dense, the orbs hint at depth
          without competing with project cards / activity feed. */}
      <AnimatedBackdrop variant="subtle" />
      {/* Backdrop for the mobile drawer — only rendered when open, on
          small screens. Click closes the drawer. */}
      {mobileNavOpen && (
        <button
          type="button"
          aria-label="Close navigation"
          onClick={closeMobileNav}
          className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm lg:hidden"
        />
      )}

      <aside
        className={cn(
          "fixed left-0 top-0 z-40 h-screen w-64 border-r border-zinc-800 bg-surface p-4 transition-transform duration-200 ease-out lg:translate-x-0 lg:bg-surface/80",
          mobileNavOpen ? "translate-x-0" : "-translate-x-full lg:translate-x-0",
        )}
      >
        <div className="flex items-center justify-between">
          <Link to="/" className="flex items-center gap-3 px-2 py-2" onClick={closeMobileNav}>
            <Logo size={40} />
            <span className="font-semibold text-zinc-50">Codeleon</span>
          </Link>
          {/* Close button — only visible inside the drawer on small screens. */}
          <button
            type="button"
            onClick={closeMobileNav}
            aria-label="Close navigation"
            className="-mr-1 inline-flex h-9 w-9 items-center justify-center rounded-md text-zinc-400 transition hover:bg-surfaceRaised hover:text-zinc-100 lg:hidden"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <nav className="mt-8 space-y-1 text-sm text-zinc-400">
          <a className="flex items-center gap-3 rounded-md bg-surfaceRaised px-3 py-2 text-zinc-100" href="#projects" onClick={closeMobileNav}>
            <Users className="h-4 w-4" />
            My projects
          </a>
          <a className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-surfaceRaised hover:text-zinc-100" href="#public" onClick={closeMobileNav}>
            <Radio className="h-4 w-4" />
            Public projects
          </a>
          <a className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-surfaceRaised hover:text-zinc-100" href="#integrations" onClick={closeMobileNav}>
            <Link2 className="h-4 w-4" />
            Integrations
          </a>
        </nav>
      </aside>

      <MotionPage className="relative z-10 lg:pl-64">
        <header className="flex items-center justify-between gap-2 border-b border-zinc-800 bg-background/90 px-3 py-3 backdrop-blur sm:px-4 sm:py-4 lg:px-8">
          <div className="flex min-w-0 items-center gap-2 sm:gap-3">
            {/* Hamburger to open the mobile nav drawer — hidden on lg+
                where the sidebar is already permanently visible. */}
            <button
              type="button"
              onClick={() => setMobileNavOpen(true)}
              aria-label="Open navigation"
              className="inline-flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-zinc-800 text-zinc-300 transition hover:border-zinc-700 hover:text-zinc-100 lg:hidden"
            >
              <Menu className="h-4 w-4" />
            </button>
            <div className="min-w-0">
              <p className="hidden text-sm text-zinc-400 sm:block">Dashboard</p>
              <h1 className="truncate text-base font-semibold text-zinc-50 sm:text-xl">
                Welcome, {user?.fullName ?? "builder"}
              </h1>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {/* Activity panel toggle — only visible on xl+ where the panel
                is actually rendered alongside the main column. */}
            <button
              type="button"
              onClick={() => setActivityOpen((v) => !v)}
              className="hidden xl:inline-flex items-center gap-2 rounded-md border border-zinc-800 bg-surface px-3 py-2 text-sm text-zinc-300 transition hover:border-zinc-700 hover:text-zinc-100"
              title={activityOpen ? "Hide activity panel" : "Show activity panel"}
              aria-pressed={activityOpen}
            >
              {activityOpen ? <PanelRightClose className="h-4 w-4" /> : <PanelRightOpen className="h-4 w-4" />}
              <span>{activityOpen ? "Hide activity" : "Show activity"}</span>
            </button>
            <Button variant="secondary" onClick={handleLogout} title="Log out">
              <LogOut className="h-4 w-4" />
              {/* Label hidden on phones to save header width. */}
              <span className="hidden sm:inline">Logout</span>
            </Button>
          </div>
        </header>

        <div className="mx-auto w-full max-w-7xl px-4 py-8 lg:px-8">
          <div className={cn(
            "grid gap-8",
            // When the activity panel is open we use a 2-column layout on xl;
            // when collapsed the main column takes the full width.
            activityOpen ? "xl:grid-cols-[minmax(0,1fr)_320px]" : "xl:grid-cols-1",
          )}>
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
                onSubmit={createForm.handleSubmit((values) => createRoomMutation.mutate(values))}
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

                <ProjectSourcePicker
                  source={createSource}
                  onSourceChange={(source) => {
                    setCreateSource(source);
                    if (source !== "blank") {
                      createForm.setValue("templateId", "", { shouldDirty: true });
                    }
                  }}
                  localImportName={localImportName}
                  localImportReport={localImportReport}
                  onPickLocal={() => localImportInputRef.current?.click()}
                  githubRepositories={filteredGithubRepositories}
                  githubRepositoriesCount={githubRepositories.length}
                  githubRepositoriesLoading={githubRepositoriesQuery.isLoading}
                  githubRepositoriesError={githubRepositoriesQuery.isError ? getErrorMessage(githubRepositoriesQuery.error) : null}
                  githubRepoSearch={githubRepoSearch}
                  onGithubRepoSearchChange={setGithubRepoSearch}
                  githubRepoUrl={githubRepoUrl}
                  onGithubRepoUrlChange={setGithubRepoUrl}
                  githubBranch={githubBranch}
                  onGithubBranchChange={setGithubBranch}
                  onSelectGithubRepository={(repository) => {
                    setGithubRepoUrl(repository.fullName);
                    setGithubBranch(repository.defaultBranch ?? "");
                    if (!createForm.getValues("name").trim()) {
                      createForm.setValue("name", repository.name ?? repository.fullName.split("/").at(-1) ?? repository.fullName, {
                        shouldDirty: true,
                        shouldValidate: true,
                      });
                    }
                  }}
                />
                <input
                  ref={localImportInputRef}
                  type="file"
                  className="hidden"
                  // @ts-expect-error webkitdirectory is supported by Chromium and carries webkitRelativePath.
                  webkitdirectory=""
                  multiple
                  onChange={(event) => {
                    void handleDashboardLocalFiles(event.currentTarget.files);
                    event.currentTarget.value = "";
                  }}
                />

                {createSource === "blank" && (
                  <ProjectTypePicker
                    templates={projectTemplates}
                    isLoading={templatesQuery.isLoading}
                    selectedTemplate={selectedTemplate}
                    selectedId={selectedTemplateId ?? ""}
                    onChange={(id) => createForm.setValue("templateId", id, { shouldDirty: true })}
                  />
                )}

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
                  {createRoomMutation.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
                  {createRoomMutation.isPending ? "Creating..." : createSource === "blank" ? "Create project" : "Create and import"}
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

            {activityOpen && (
              <aside className="hidden xl:block">
                <div className="sticky top-8 space-y-4">
                  <ActivityFeed currentUserId={user?.id} />
                </div>
              </aside>
            )}
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
  const [params] = useSearchParams();
  const oauthConnected = params.get("oauth_connected");
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

      <AnimatePresence>
        {oauthConnected === "github" && (
          <motion.div
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -4 }}
            className="mt-4 flex items-start gap-2 rounded-md border border-emerald-800 bg-emerald-950/30 px-3 py-2 text-xs text-emerald-200"
          >
            <Check className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            GitHub is connected. If GitHub skipped the authorization screen, it means this account had already approved Codeleon.
          </motion.div>
        )}
      </AnimatePresence>

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
            <a
              href={`${API_BASE_URL}/oauth2/authorization/${provider}`}
              onClick={() => {
                window.sessionStorage.setItem("codeleon.oauth.linkIntent", provider);
              }}
            >
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

function ProjectSourcePicker({
  githubBranch,
  githubRepoSearch,
  githubRepoUrl,
  githubRepositories,
  githubRepositoriesCount,
  githubRepositoriesError,
  githubRepositoriesLoading,
  localImportName,
  localImportReport,
  onGithubBranchChange,
  onGithubRepoSearchChange,
  onGithubRepoUrlChange,
  onPickLocal,
  onSelectGithubRepository,
  onSourceChange,
  source,
}: {
  githubBranch: string;
  githubRepoSearch: string;
  githubRepoUrl: string;
  githubRepositories: GithubRepository[];
  githubRepositoriesCount: number;
  githubRepositoriesError: string | null;
  githubRepositoriesLoading: boolean;
  localImportName: string | null;
  localImportReport: ImportFilterReport | null;
  onGithubBranchChange: (value: string) => void;
  onGithubRepoSearchChange: (value: string) => void;
  onGithubRepoUrlChange: (value: string) => void;
  onPickLocal: () => void;
  onSelectGithubRepository: (repository: GithubRepository) => void;
  onSourceChange: (source: CreateProjectSource) => void;
  source: CreateProjectSource;
}) {
  const options: { id: CreateProjectSource; label: string; icon: JSX.Element }[] = [
    { id: "blank", label: "Blank / template", icon: <FileCode2 className="h-4 w-4" /> },
    { id: "local", label: "Local device", icon: <Upload className="h-4 w-4" /> },
    { id: "github", label: "GitHub repo", icon: <Github className="h-4 w-4" /> },
  ];

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
        <Sparkles className="h-4 w-4 text-cyan" />
        Project source
      </div>
      <div className="grid gap-2 sm:grid-cols-3">
        {options.map((option) => (
          <button
            key={option.id}
            type="button"
            onClick={() => onSourceChange(option.id)}
            className={cn(
              "flex min-h-11 items-center justify-center gap-2 rounded-md border px-3 py-2 text-xs transition",
              source === option.id
                ? "border-cyan/50 bg-cyan/10 text-cyan"
                : "border-zinc-800 bg-zinc-950 text-zinc-400 hover:border-zinc-700 hover:text-zinc-200",
            )}
            aria-pressed={source === option.id}
          >
            {option.icon}
            {option.label}
          </button>
        ))}
      </div>

      <AnimatePresence mode="wait">
        {source === "local" && (
          <motion.div
            key="local-source"
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3"
          >
            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="min-w-0">
                <p className="text-sm font-medium text-zinc-200">
                  {localImportName ?? "Choose a project folder"}
                </p>
                <p className="mt-1 text-xs text-zinc-500">
                  {localImportReport
                    ? `${localImportReport.prepared.length} ready, ${localImportReport.skipped.length} skipped${localImportReport.truncated ? ", truncated at 200 files" : ""}`
                    : "Imports text/code files and preserves nested folders."}
                </p>
              </div>
              <Button type="button" variant="secondary" onClick={onPickLocal}>
                <Upload className="h-4 w-4" />
                Browse
              </Button>
            </div>
          </motion.div>
        )}

        {source === "github" && (
          <motion.div
            key="github-source"
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            className="space-y-3 rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3"
          >
            {githubRepositoriesLoading && (
              <div className="flex items-center gap-2 text-xs text-zinc-500">
                <Loader2 className="h-3.5 w-3.5 animate-spin text-cyan" />
                Loading connected GitHub repositories...
              </div>
            )}

            {githubRepositoriesError && (
              <div className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-zinc-800 bg-surface px-3 py-2">
                <p className="text-xs text-zinc-500">Connect GitHub to browse repositories, or paste a public repository URL below.</p>
                <Button asChild type="button" variant="secondary" className="h-8 px-3 text-xs">
                  <a
                    href={`${API_BASE_URL}/oauth2/authorization/github`}
                    onClick={() => window.sessionStorage.setItem("codeleon.oauth.linkIntent", "github")}
                  >
                    <Github className="h-3.5 w-3.5" />
                    Connect
                  </a>
                </Button>
              </div>
            )}

            {githubRepositoriesCount > 0 && (
              <div className="space-y-2">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-xs uppercase tracking-wide text-zinc-500">Your GitHub repositories</span>
                  <span className="text-[11px] text-zinc-600">{githubRepositoriesCount} found</span>
                </div>
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-600" />
                  <input
                    value={githubRepoSearch}
                    onChange={(event) => onGithubRepoSearchChange(event.target.value)}
                    placeholder="Search repositories..."
                    className="h-9 w-full rounded-md border border-zinc-800 bg-background px-3 pl-9 text-sm text-zinc-100 outline-none placeholder:text-zinc-600 focus:border-cyan"
                  />
                </div>
                <div className="max-h-48 overflow-y-auto rounded-md border border-zinc-800 bg-background p-1">
                  {githubRepositories.length > 0 ? (
                    githubRepositories.slice(0, 30).map((repository) => (
                      <button
                        key={repository.fullName}
                        type="button"
                        onClick={() => onSelectGithubRepository(repository)}
                        className={cn(
                          "flex w-full items-center justify-between gap-3 rounded px-3 py-2 text-left transition",
                          githubRepoUrl === repository.fullName
                            ? "bg-cyan/10 text-zinc-50"
                            : "text-zinc-300 hover:bg-surfaceRaised hover:text-zinc-50",
                        )}
                      >
                        <span className="min-w-0">
                          <span className="flex items-center gap-2">
                            <span className="truncate font-mono text-xs">{repository.fullName}</span>
                            {repository.privateRepo && <Lock className="h-3 w-3 shrink-0 text-zinc-500" />}
                          </span>
                          <span className="mt-1 block truncate text-xs text-zinc-600">
                            {repository.description ?? "No description"}
                          </span>
                        </span>
                        {repository.defaultBranch && (
                          <span className="shrink-0 font-mono text-[10px] text-zinc-600">{repository.defaultBranch}</span>
                        )}
                      </button>
                    ))
                  ) : (
                    <p className="px-3 py-5 text-center text-xs text-zinc-500">No repositories match your search.</p>
                  )}
                </div>
              </div>
            )}

            <div className="grid gap-2 sm:grid-cols-[minmax(0,1fr)_9rem]">
              <label className="block">
                <span className="mb-1 block text-xs uppercase tracking-wide text-zinc-500">Repository URL</span>
                <input
                  value={githubRepoUrl}
                  onChange={(event) => onGithubRepoUrlChange(event.target.value)}
                  placeholder="owner/repo or https://github.com/owner/repo"
                  className="h-9 w-full rounded-md border border-zinc-800 bg-background px-3 font-mono text-xs text-zinc-100 outline-none placeholder:text-zinc-600 focus:border-cyan"
                />
              </label>
              <label className="block">
                <span className="mb-1 block text-xs uppercase tracking-wide text-zinc-500">Branch</span>
                <input
                  value={githubBranch}
                  onChange={(event) => onGithubBranchChange(event.target.value)}
                  placeholder="main"
                  className="h-9 w-full rounded-md border border-zinc-800 bg-background px-3 font-mono text-xs text-zinc-100 outline-none placeholder:text-zinc-600 focus:border-cyan"
                />
              </label>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}

function ProjectTypePicker({
  templates,
  isLoading,
  selectedTemplate,
  selectedId,
  onChange,
}: {
  templates: ProjectTemplate[];
  isLoading: boolean;
  selectedTemplate: ProjectTemplate | null;
  selectedId: string;
  onChange: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const filteredTemplates = useMemo(() => filterProjectTemplates(templates, query), [templates, query]);
  const grouped = useMemo(() => groupTemplates(filteredTemplates), [filteredTemplates]);

  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2 text-sm font-medium text-zinc-200">
        <Sparkles className="h-4 w-4 text-cyan" />
        Project type
      </div>
      <div className="relative">
        <button
          type="button"
          onClick={() => setOpen((current) => !current)}
          className="flex min-h-12 w-full items-center justify-between gap-3 rounded-md border border-zinc-800 bg-zinc-950 px-3 text-left transition hover:border-zinc-700"
          aria-expanded={open}
        >
          <span className="min-w-0">
            <span className="block truncate text-sm font-medium text-zinc-100">
              {selectedTemplate?.name ?? "Choose a project type"}
            </span>
            <span className="mt-0.5 block truncate text-xs text-zinc-500">
              {selectedTemplate
                ? `${selectedTemplate.category} / ${selectedTemplate.runtime ?? selectedTemplate.language}`
                : "Starter files, Nix command, preview, and service metadata"}
            </span>
          </span>
          <ChevronDown className={cn("h-4 w-4 shrink-0 text-zinc-500 transition", open && "rotate-180")} />
        </button>

        <AnimatePresence>
          {open && (
            <motion.div
              initial={{ opacity: 0, y: -6 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -6 }}
              className="absolute z-30 mt-2 w-full overflow-hidden rounded-md border border-zinc-800 bg-zinc-950 shadow-2xl shadow-black/40"
            >
              <div className="border-b border-zinc-800 p-2">
                <div className="relative">
                  <Search className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-600" />
                  <input
                    value={query}
                    onChange={(event) => setQuery(event.target.value)}
                    placeholder="Search languages, databases, frameworks..."
                    className="h-9 w-full rounded-md border border-zinc-800 bg-background px-3 pl-9 text-sm text-zinc-100 outline-none placeholder:text-zinc-600 focus:border-cyan"
                  />
                </div>
              </div>

              <div className="max-h-80 overflow-y-auto p-2">
                <button
                  type="button"
                  onClick={() => {
                    onChange("");
                    setOpen(false);
                    setQuery("");
                  }}
                  className={cn(
                    "mb-2 flex w-full items-center justify-between gap-3 rounded-md border px-3 py-2 text-left transition",
                    !selectedId
                      ? "border-cyan/50 bg-cyan/10 text-zinc-50"
                      : "border-zinc-800 bg-background text-zinc-300 hover:border-zinc-700 hover:bg-surfaceRaised",
                  )}
                >
                  <span>
                    <span className="block text-sm font-medium">Blank project</span>
                    <span className="text-xs text-zinc-500">No starter template</span>
                  </span>
                  <FileCode2 className="h-4 w-4 text-zinc-500" />
                </button>

                {isLoading ? (
                  <div className="space-y-2">
                    {[0, 1, 2].map((index) => (
                      <div key={index} className="codeleon-shimmer h-12 rounded-md bg-zinc-900" />
                    ))}
                  </div>
                ) : grouped.length > 0 ? (
                  grouped.map(([category, items]) => (
                    <div key={category} className="mb-3 last:mb-0">
                      <p className="mb-1 px-1 text-[11px] font-medium uppercase tracking-wide text-zinc-500">{category}</p>
                      <div className="space-y-1">
                        {items.map((template) => (
                          <button
                            key={template.id}
                            type="button"
                            onClick={() => {
                              onChange(template.id);
                              setOpen(false);
                              setQuery("");
                            }}
                            className={cn(
                              "flex w-full items-start justify-between gap-3 rounded-md px-3 py-2 text-left transition",
                              selectedId === template.id
                                ? "bg-cyan/10 text-zinc-50"
                                : "text-zinc-300 hover:bg-surfaceRaised hover:text-zinc-50",
                            )}
                          >
                            <span className="min-w-0">
                              <span className="block truncate text-sm font-medium">{template.name}</span>
                              <span className="mt-0.5 block truncate text-xs text-zinc-500">{template.description}</span>
                            </span>
                            <span className="shrink-0 rounded border border-zinc-800 px-1.5 py-0.5 text-[10px] text-zinc-500">
                              {template.language}
                            </span>
                          </button>
                        ))}
                      </div>
                    </div>
                  ))
                ) : (
                  <p className="px-3 py-5 text-center text-xs text-zinc-500">No project types match your search.</p>
                )}
              </div>
            </motion.div>
          )}
        </AnimatePresence>
      </div>
      <ProjectTypePreview template={selectedTemplate} />
    </div>
  );
}

function ProjectTypePreview({ template }: { template: ProjectTemplate | null }) {
  if (!template) {
    return (
      <div className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3 text-xs text-zinc-500">
        Choose a starter to create files with content, Nix commands, preview support, and optional services.
      </div>
    );
  }

  return (
    <motion.div layout className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-sm font-medium text-zinc-100">{template.name}</p>
          <p className="mt-1 text-xs leading-5 text-zinc-500">{template.description}</p>
        </div>
        <span className="shrink-0 rounded-md border border-cyan/30 bg-cyan/10 px-2 py-1 text-[11px] text-cyan">
          {template.category}
        </span>
      </div>
      <div className="mt-3 grid gap-2 text-xs text-zinc-500 sm:grid-cols-2">
        <PreviewFact icon={<Terminal className="h-3.5 w-3.5" />} label={template.defaultCommand ?? "No command"} />
        <PreviewFact icon={<FileCode2 className="h-3.5 w-3.5" />} label={`${template.fileCount} ${template.fileCount === 1 ? "file" : "files"}`} />
        <PreviewFact icon={<ShieldCheck className="h-3.5 w-3.5" />} label={template.runnable ? "Runs with Nix" : "Template only"} />
        <PreviewFact icon={<Database className="h-3.5 w-3.5" />} label={template.services.length > 0 ? template.services.join(", ") : "No services"} />
      </div>
      {template.preview && (
        <p className="mt-3 rounded-md border border-emerald-800/60 bg-emerald-950/30 px-2 py-1 text-xs text-emerald-300">
          Static preview available in the room.
        </p>
      )}
    </motion.div>
  );
}

function PreviewFact({ icon, label }: { icon: JSX.Element; label: string }) {
  return (
    <span className="flex min-w-0 items-center gap-1.5 rounded-md border border-zinc-800 bg-background px-2 py-1">
      {icon}
      <span className="truncate">{label}</span>
    </span>
  );
}

function filterProjectTemplates(templates: ProjectTemplate[], query: string) {
  const needle = query.trim().toLowerCase();
  if (!needle) return templates;
  return templates.filter((template) =>
    [
      template.name,
      template.description,
      template.language,
      template.category,
      template.runtime ?? "",
      template.packageManager ?? "",
      ...template.tags,
      ...template.services,
    ]
      .join(" ")
      .toLowerCase()
      .includes(needle),
  );
}

function groupTemplates(templates: ProjectTemplate[]): Array<[string, ProjectTemplate[]]> {
  const groups = new Map<string, ProjectTemplate[]>();
  for (const template of templates) {
    const category = template.category || "General";
    groups.set(category, [...(groups.get(category) ?? []), template]);
  }
  return Array.from(groups.entries());
}

function getErrorMessage(error: unknown) {
  if (error instanceof AxiosError) {
    return error.response?.data?.message ?? "Request failed";
  }
  if (error instanceof Error) {
    return error.message;
  }
  return "Request failed";
}

function encodeFilesSnapshot(files: PreparedFile[]): Uint8Array {
  const doc = new Y.Doc();
  for (const file of files) {
    const yText = doc.getText(file.path);
    if (file.content.length > 0) {
      yText.insert(0, file.content);
    }
  }
  return Y.encodeStateAsUpdate(doc);
}

function inferLocalProjectName(fileList: FileList): string | null {
  const first = fileList.item(0) as (File & { webkitRelativePath?: string }) | null;
  const rawPath = first?.webkitRelativePath;
  if (!rawPath) {
    return null;
  }
  const [folder] = rawPath.replace(/\\/g, "/").split("/");
  return folder?.trim() || null;
}

function filterGithubRepositories(repositories: GithubRepository[], query: string) {
  const trimmed = query.trim().toLowerCase();
  if (!trimmed) {
    return repositories;
  }
  return repositories.filter((repository) =>
    [
      repository.fullName,
      repository.owner,
      repository.name,
      repository.description,
      repository.defaultBranch,
    ]
      .filter(Boolean)
      .join(" ")
      .toLowerCase()
      .includes(trimmed),
  );
}
