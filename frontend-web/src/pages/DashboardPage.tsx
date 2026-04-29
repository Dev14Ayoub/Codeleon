import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { Braces, DoorOpen, Globe2, Lock, LogOut, Plus, Radio, Users } from "lucide-react";
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { FieldError } from "@/components/ui/field-error";
import { Input } from "@/components/ui/input";
import { createRoom, fetchCurrentUser, fetchMyRooms, fetchPublicRooms, joinRoom, type Room } from "@/lib/api";
import { CreateRoomValues, JoinRoomValues, createRoomSchema, joinRoomSchema } from "@/lib/validators";
import { useAuthStore } from "@/stores/auth-store";

export function DashboardPage() {
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const logout = useAuthStore((state) => state.logout);
  const storedUser = useAuthStore((state) => state.user);
  const setUser = useAuthStore((state) => state.setUser);
  const createForm = useForm<CreateRoomValues>({
    resolver: zodResolver(createRoomSchema),
    defaultValues: {
      name: "",
      description: "",
      visibility: "PRIVATE",
    },
  });
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

  const myRoomsQuery = useQuery({
    queryKey: ["rooms", "mine"],
    queryFn: fetchMyRooms,
  });

  const publicRoomsQuery = useQuery({
    queryKey: ["rooms", "public"],
    queryFn: fetchPublicRooms,
  });

  const createRoomMutation = useMutation({
    mutationFn: createRoom,
    onSuccess: () => {
      createForm.reset({ name: "", description: "", visibility: "PRIVATE" });
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

  return (
    <main className="min-h-screen bg-background">
      <aside className="fixed left-0 top-0 hidden h-screen w-64 border-r border-zinc-800 bg-surface/80 p-4 lg:block">
        <Link to="/" className="flex items-center gap-3 px-2 py-2">
          <span className="flex h-10 w-10 items-center justify-center rounded-md bg-signature text-white">
            <Braces className="h-5 w-5" />
          </span>
          <span className="font-semibold text-zinc-50">Codeleon</span>
        </Link>
        <nav className="mt-8 space-y-1 text-sm text-zinc-400">
          <a className="flex items-center gap-3 rounded-md bg-surfaceRaised px-3 py-2 text-zinc-100" href="#rooms">
            <Users className="h-4 w-4" />
            Rooms
          </a>
          <a className="flex items-center gap-3 rounded-md px-3 py-2 hover:bg-surfaceRaised hover:text-zinc-100" href="#public">
            <Radio className="h-4 w-4" />
            Public rooms
          </a>
        </nav>
      </aside>

      <section className="lg:pl-64">
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

        <div className="mx-auto w-full max-w-6xl space-y-8 px-4 py-8 lg:px-8">
          <section id="rooms" className="space-y-4">
            <div className="flex items-center justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold text-zinc-50">My rooms</h2>
                <p className="text-sm text-zinc-500">Your collaborative workspaces will appear here.</p>
              </div>
            </div>

            <div className="grid gap-4 lg:grid-cols-[1.1fr_0.9fr]">
              <form
                className="space-y-4 rounded-lg border border-zinc-800 bg-surface p-5"
                onSubmit={createForm.handleSubmit((values) =>
                  createRoomMutation.mutate({
                    ...values,
                    description: values.description?.trim() || undefined,
                  }),
                )}
              >
                <div className="flex items-center gap-2">
                  <Plus className="h-4 w-4 text-cyan" />
                  <h3 className="font-medium text-zinc-100">Create room</h3>
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
                  {createRoomMutation.isPending ? "Creating..." : "Create room"}
                </Button>
              </form>

              <form
                className="space-y-4 rounded-lg border border-zinc-800 bg-surface p-5"
                onSubmit={joinForm.handleSubmit((values) => joinRoomMutation.mutate(values))}
              >
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
                  {joinRoomMutation.isPending ? "Joining..." : "Join room"}
                </Button>
              </form>
            </div>

            <RoomGrid emptyText="No rooms yet" isLoading={myRoomsQuery.isLoading} rooms={myRoomsQuery.data ?? []} />
          </section>

          <section id="public" className="space-y-4">
            <div>
              <h2 className="text-lg font-semibold text-zinc-50">Public rooms</h2>
              <p className="text-sm text-zinc-500">Discover public programming sessions once rooms are enabled.</p>
            </div>
            <RoomGrid emptyText="No public rooms yet" isLoading={publicRoomsQuery.isLoading} rooms={publicRoomsQuery.data ?? []} />
          </section>
        </div>
      </section>
    </main>
  );
}

function RoomGrid({ emptyText, isLoading, rooms }: { emptyText: string; isLoading: boolean; rooms: Room[] }) {
  if (isLoading) {
    return <div className="rounded-lg border border-zinc-800 bg-surface/50 p-8 text-center text-sm text-zinc-500">Loading rooms...</div>;
  }

  if (rooms.length === 0) {
    return (
      <div className="rounded-lg border border-dashed border-zinc-800 bg-surface/50 p-8 text-center">
        <p className="font-medium text-zinc-200">{emptyText}</p>
        <p className="mt-2 text-sm text-zinc-500">Create or join a room to start collaborating.</p>
      </div>
    );
  }

  return (
    <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
      {rooms.map((room) => (
        <Link key={room.id} to={`/rooms/${room.id}`} className="rounded-lg border border-zinc-800 bg-surface p-5 transition hover:border-signature/60 hover:bg-surfaceRaised">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="font-medium text-zinc-100">{room.name}</p>
              <p className="mt-1 text-sm text-zinc-500">Owner: {room.ownerName}</p>
            </div>
            <span className="rounded-md border border-zinc-700 px-2 py-1 text-xs text-zinc-400">{room.visibility.toLowerCase()}</span>
          </div>
          {room.description && <p className="mt-4 text-sm leading-6 text-zinc-400">{room.description}</p>}
          <div className="mt-5 flex items-center justify-between gap-3 border-t border-zinc-800 pt-4">
            <span className="font-mono text-xs text-zinc-500">{room.inviteCode}</span>
            <span className="text-xs font-medium text-cyan">{room.currentUserRole ?? "guest"}</span>
          </div>
        </Link>
      ))}
    </div>
  );
}

function getErrorMessage(error: unknown) {
  if (error instanceof AxiosError) {
    return error.response?.data?.message ?? "Request failed";
  }
  return "Request failed";
}
