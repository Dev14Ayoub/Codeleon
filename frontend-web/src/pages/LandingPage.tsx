import { motion } from "framer-motion";
import {
  ArrowRight,
  Bot,
  CheckCircle2,
  Database,
  Github,
  Lock,
  Play,
  Radio,
  ShieldCheck,
  Terminal,
  Users,
} from "lucide-react";
import { Link } from "react-router-dom";
import { Logo } from "@/components/brand/Logo";
import { Button } from "@/components/ui/button";
import { StatusPulse, fadeUp, stagger } from "@/components/ui/motion";

const editorLines = [
  ["01", "from codeleon import Room, PrivateAI"],
  ["02", ""],
  ["03", "room = Room.open('PFE defense demo')"],
  ["04", "room.invite(['teacher', 'teammate'])"],
  ["05", "room.ai = PrivateAI.local(project='algorithms')"],
  ["06", ""],
  ["07", "answer = room.ai.ask('why does this test fail?')"],
  ["08", "room.run('tests/test_fibonacci.py')"],
];

const events = [
  { icon: <Users className="h-3.5 w-3.5" />, text: "Badr joined the room", tone: "text-cyan" },
  { icon: <Database className="h-3.5 w-3.5" />, text: "AI indexed 38 chunks", tone: "text-emerald-300" },
  { icon: <Play className="h-3.5 w-3.5" />, text: "Sandbox run passed", tone: "text-violet" },
];

export function LandingPage() {
  return (
    <main className="min-h-screen overflow-hidden bg-background">
      <section className="relative min-h-screen overflow-hidden">
        <CommandBackdrop />

        <nav className="relative z-20 mx-auto flex w-full max-w-7xl items-center justify-between px-4 py-6 lg:px-8">
          <Link to="/" className="flex items-center gap-3">
            <Logo size={40} />
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

        <motion.div
          initial="hidden"
          animate="show"
          variants={stagger}
          className="relative z-20 mx-auto flex min-h-[calc(100vh-88px)] w-full max-w-7xl items-center px-4 pb-20 pt-8 lg:px-8"
        >
          <div className="max-w-3xl">
            <motion.div variants={fadeUp} className="inline-flex items-center gap-2 rounded-md border border-cyan/30 bg-cyan/10 px-3 py-2 text-sm text-cyan backdrop-blur">
              <StatusPulse className="scale-75" />
              Live collaborative AI workspace
            </motion.div>

            <motion.h1 variants={fadeUp} className="mt-8 max-w-4xl text-5xl font-semibold leading-tight text-zinc-50 md:text-7xl">
              Codeleon
            </motion.h1>

            <motion.p variants={fadeUp} className="mt-5 max-w-2xl text-2xl font-medium leading-9 text-zinc-200 md:text-3xl">
              A private coding room that feels alive.
            </motion.p>

            <motion.p variants={fadeUp} className="mt-5 max-w-2xl text-base leading-7 text-zinc-400 md:text-lg">
              Build with teammates, import real projects, run code in a sandbox,
              and ask local AI questions about your own files without turning the workspace into noise.
            </motion.p>

            <motion.div variants={fadeUp} className="mt-8 flex flex-col gap-3 sm:flex-row">
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
            </motion.div>

            <motion.div variants={fadeUp} className="mt-10 grid max-w-3xl gap-3 sm:grid-cols-3">
              <Signal icon={<Radio className="h-4 w-4" />} title="Live" text="multi-cursor rooms" />
              <Signal icon={<Bot className="h-4 w-4" />} title="Private" text="local AI context" />
              <Signal icon={<ShieldCheck className="h-4 w-4" />} title="Safe" text="sandboxed runner" />
            </motion.div>
          </div>
        </motion.div>
      </section>
    </main>
  );
}

function CommandBackdrop() {
  return (
    <div className="pointer-events-none absolute inset-0">
      <div className="codeleon-grid absolute inset-0 opacity-70" />
      <div className="absolute inset-0 bg-[linear-gradient(90deg,#09090b_0%,rgba(9,9,11,0.94)_36%,rgba(9,9,11,0.70)_68%,rgba(9,9,11,0.92)_100%)]" />

      <motion.div
        aria-hidden
        initial={{ opacity: 0, x: 80, rotate: -4 }}
        animate={{ opacity: 1, x: 0, rotate: -4 }}
        transition={{ duration: 0.9, ease: [0.16, 1, 0.3, 1] }}
        className="absolute bottom-[-3rem] right-[-18rem] top-28 hidden w-[62rem] rounded-lg border border-zinc-800/80 bg-zinc-950/80 shadow-[0_30px_140px_rgba(0,0,0,0.65)] backdrop-blur-xl lg:block"
      >
        <div className="flex h-12 items-center justify-between border-b border-zinc-800 px-5">
          <div className="flex items-center gap-2">
            <span className="h-3 w-3 rounded-full bg-danger" />
            <span className="h-3 w-3 rounded-full bg-warning" />
            <span className="h-3 w-3 rounded-full bg-success" />
            <span className="ml-4 font-mono text-xs text-zinc-500">codeleon://room/pfe-demo</span>
          </div>
          <div className="inline-flex items-center gap-2 rounded-md border border-emerald-800 bg-emerald-950/40 px-2 py-1 text-xs text-emerald-300">
            <CheckCircle2 className="h-3.5 w-3.5" />
            indexed
          </div>
        </div>

        <div className="grid h-[calc(100%-3rem)] grid-cols-[minmax(0,1fr)_18rem]">
          <div className="relative overflow-hidden p-6">
            <pre className="font-mono text-sm leading-8 text-zinc-300">
              {editorLines.map(([num, line], index) => (
                <motion.code
                  key={`${num}-${line}`}
                  initial={{ opacity: 0, x: -10 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.25 + index * 0.08 }}
                  className="block"
                >
                  <span className="mr-5 select-none text-zinc-700">{num}</span>
                  {line}
                </motion.code>
              ))}
            </pre>

            <motion.div
              initial={{ opacity: 0, y: 14 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ delay: 0.95 }}
              className="absolute bottom-6 left-6 right-6 rounded-md border border-zinc-800 bg-background/95 px-4 py-3"
            >
              <div className="mb-2 flex items-center gap-2 text-xs text-zinc-400">
                <Terminal className="h-3.5 w-3.5 text-cyan" />
                sandbox output
                <span className="ml-auto text-emerald-400">exit 0</span>
              </div>
              <p className="font-mono text-xs text-zinc-300">8 tests passed in 0.42s</p>
            </motion.div>
          </div>

          <div className="border-l border-zinc-800 bg-surface/60 p-4">
            <div className="mb-4 flex items-center gap-2 text-sm font-medium text-zinc-200">
              <Lock className="h-4 w-4 text-cyan" />
              Room signal
            </div>
            <div className="space-y-3">
              {events.map((event, index) => (
                <motion.div
                  key={event.text}
                  initial={{ opacity: 0, y: 8 }}
                  animate={{ opacity: 1, y: 0 }}
                  transition={{ delay: 0.45 + index * 0.12 }}
                  className="rounded-md border border-zinc-800 bg-zinc-950 px-3 py-2 text-xs text-zinc-400"
                >
                  <span className={`mr-2 inline-flex align-middle ${event.tone}`}>{event.icon}</span>
                  {event.text}
                </motion.div>
              ))}
            </div>
          </div>
        </div>
      </motion.div>
    </div>
  );
}

function Signal({ icon, title, text }: { icon: JSX.Element; title: string; text: string }) {
  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-950/55 px-4 py-3 backdrop-blur transition hover:border-cyan/40 hover:bg-surface/70">
      <div className="flex items-center gap-2 text-sm font-medium text-zinc-100">
        <span className="text-cyan">{icon}</span>
        {title}
      </div>
      <p className="mt-1 text-xs text-zinc-500">{text}</p>
    </div>
  );
}
