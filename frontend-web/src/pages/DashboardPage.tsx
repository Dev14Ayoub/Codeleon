import { useQuery } from "@tanstack/react-query";
import { Braces, LogOut, Plus, Radio, Users } from "lucide-react";
import { useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { fetchCurrentUser } from "@/lib/api";
import { useAuthStore } from "@/stores/auth-store";

export function DashboardPage() {
  const navigate = useNavigate();
  const logout = useAuthStore((state) => state.logout);
  const storedUser = useAuthStore((state) => state.user);
  const setUser = useAuthStore((state) => state.setUser);

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
              <Button disabled>
                <Plus className="h-4 w-4" />
                New room
              </Button>
            </div>
            <div className="rounded-lg border border-dashed border-zinc-800 bg-surface/50 p-8 text-center">
              <p className="font-medium text-zinc-200">No rooms yet</p>
              <p className="mt-2 text-sm text-zinc-500">Room creation is the next milestone after authentication.</p>
            </div>
          </section>

          <section id="public" className="space-y-4">
            <div>
              <h2 className="text-lg font-semibold text-zinc-50">Public rooms</h2>
              <p className="text-sm text-zinc-500">Discover public programming sessions once rooms are enabled.</p>
            </div>
            <div className="grid gap-4 md:grid-cols-3">
              {["Algorithms", "Spring Boot", "React Lab"].map((name) => (
                <article key={name} className="rounded-lg border border-zinc-800 bg-surface p-5">
                  <p className="font-medium text-zinc-100">{name}</p>
                  <p className="mt-2 text-sm leading-6 text-zinc-500">Coming soon</p>
                </article>
              ))}
            </div>
          </section>
        </div>
      </section>
    </main>
  );
}
