import { motion } from "framer-motion";
import { ArrowRight, Braces, Github, Sparkles, Users } from "lucide-react";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";

export function LandingPage() {
  return (
    <main className="min-h-screen overflow-hidden">
      <nav className="mx-auto flex w-full max-w-6xl items-center justify-between px-4 py-6">
        <Link to="/" className="flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-md bg-signature text-white">
            <Braces className="h-5 w-5" />
          </span>
          <span className="font-semibold text-zinc-50">Codeleon</span>
        </Link>
        <div className="flex items-center gap-2">
          <Button asChild variant="ghost" className="hidden sm:inline-flex">
            <Link to="/login">Sign in</Link>
          </Button>
          <Button asChild>
            <Link to="/signup">Start building</Link>
          </Button>
        </div>
      </nav>

      <section className="mx-auto grid min-h-[calc(100vh-88px)] w-full max-w-6xl items-center gap-10 px-4 pb-16 pt-10 lg:grid-cols-[1.05fr_0.95fr]">
        <motion.div
          initial={{ opacity: 0, y: 24 }}
          animate={{ opacity: 1, y: 0 }}
          transition={{ duration: 0.5 }}
          className="space-y-8"
        >
          <div className="inline-flex items-center gap-2 rounded-md border border-zinc-800 bg-surface px-3 py-2 text-sm text-zinc-300">
            <Sparkles className="h-4 w-4 text-cyan" />
            Local AI, real-time collaboration, secure execution
          </div>

          <div className="space-y-5">
            <h1 className="max-w-3xl text-5xl font-semibold leading-tight text-zinc-50 md:text-7xl">
              Codeleon
            </h1>
            <p className="max-w-2xl text-xl leading-8 text-zinc-300">
              Code adapts. Teams thrive.
            </p>
            <p className="max-w-2xl leading-7 text-zinc-400">
              A collaborative programming workspace with rooms, a Monaco editor, code execution, and a local RAG assistant that understands each project.
            </p>
          </div>

          <div className="flex flex-col gap-3 sm:flex-row">
            <Button asChild className="h-11">
              <Link to="/signup">
                Create workspace
                <ArrowRight className="h-4 w-4" />
              </Link>
            </Button>
            <Button asChild className="h-11" variant="secondary">
              <Link to="/login">
                <Github className="h-4 w-4" />
                Open dashboard
              </Link>
            </Button>
          </div>
        </motion.div>

        <motion.div
          initial={{ opacity: 0, scale: 0.96 }}
          animate={{ opacity: 1, scale: 1 }}
          transition={{ duration: 0.5, delay: 0.1 }}
          className="rounded-lg border border-zinc-800 bg-zinc-950 shadow-glow"
        >
          <div className="flex items-center gap-2 border-b border-zinc-800 px-4 py-3">
            <span className="h-3 w-3 rounded-full bg-danger" />
            <span className="h-3 w-3 rounded-full bg-warning" />
            <span className="h-3 w-3 rounded-full bg-success" />
            <span className="ml-3 font-mono text-xs text-zinc-500">room/main.ts</span>
          </div>
          <div className="grid min-h-[420px] grid-cols-[1fr_15rem]">
            <pre className="overflow-hidden p-5 font-mono text-sm leading-7 text-zinc-300">
              <code>{`type Room = {
  name: "Codeleon";
  members: TeamMember[];
  assistant: LocalRagAgent;
};

async function collaborate(room: Room) {
  await room.assistant.indexProject();
  return room.members.map((member) =>
    member.buildWithContext()
  );
}`}</code>
            </pre>
            <aside className="hidden border-l border-zinc-800 bg-surface/60 p-4 md:block">
              <div className="mb-4 flex items-center gap-2 text-sm font-medium text-zinc-200">
                <Users className="h-4 w-4 text-cyan" />
                Online
              </div>
              {["Badr", "Leo AI", "Reviewer"].map((name, index) => (
                <div key={name} className="mb-3 flex items-center gap-3 text-sm text-zinc-400">
                  <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: ["#6366F1", "#06B6D4", "#8B5CF6"][index] }} />
                  {name}
                </div>
              ))}
            </aside>
          </div>
        </motion.div>
      </section>
    </main>
  );
}
