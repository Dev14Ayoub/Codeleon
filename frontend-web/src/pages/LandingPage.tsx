import { motion, useReducedMotion } from "framer-motion";
import {
  ArrowRight,
  Bot,
  Boxes,
  CheckCircle2,
  Cpu,
  Database,
  GitBranch,
  Github,
  Lock,
  Play,
  Radio,
  ShieldCheck,
  Sparkles,
  Terminal,
  Users,
  Wand2,
  Zap,
} from "lucide-react";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Logo } from "@/components/brand/Logo";
import { Button } from "@/components/ui/button";
import { StatusPulse, fadeUp, stagger } from "@/components/ui/motion";

const typedLines = [
  "from codeleon import Room, PrivateAI",
  "",
  "room = Room.open('pfe-defense-demo')",
  "room.invite(['badr', 'teacher', 'soufiane'])",
  "",
  "ai = PrivateAI.local(project='algorithms')",
  "answer = ai.ask('why does test_fib fail?')",
  "room.run('tests/test_fibonacci.py')",
];

const events = [
  { icon: <Users className="h-3.5 w-3.5" />, text: "Badr joined the room", tone: "text-cyan" },
  { icon: <Database className="h-3.5 w-3.5" />, text: "AI indexed 38 chunks", tone: "text-emerald-300" },
  { icon: <Play className="h-3.5 w-3.5" />, text: "Sandbox run passed", tone: "text-violet" },
];

export function LandingPage() {
  return (
    <main className="relative min-h-screen overflow-hidden bg-background text-zinc-100">
      <AuroraBackdrop />

      <div className="relative z-10">
        <NavBar />
        <Hero />
        <HowItWorks />
        <BentoGrid />
        <FinalCta />
        <Footer />
      </div>
    </main>
  );
}

function AuroraBackdrop() {
  return (
    <div className="codeleon-backdrop" aria-hidden>
      <div className="codeleon-orb codeleon-orb--indigo" />
      <div className="codeleon-orb codeleon-orb--cyan" />
      <div className="codeleon-orb codeleon-orb--violet" />
      <div className="codeleon-grid absolute inset-0 opacity-50" />
    </div>
  );
}

function NavBar() {
  return (
    <nav className="mx-auto flex w-full max-w-7xl items-center justify-between px-4 py-6 lg:px-8">
      <Link to="/" className="flex items-center gap-3">
        <Logo size={36} />
        <span className="font-semibold text-zinc-50">Codeleon</span>
      </Link>
      <div className="hidden items-center gap-6 text-sm text-zinc-400 md:flex">
        <a href="#how" className="transition hover:text-zinc-100">How it works</a>
        <a href="#features" className="transition hover:text-zinc-100">Features</a>
        <a href="#cta" className="transition hover:text-zinc-100">Get started</a>
      </div>
      <div className="flex items-center gap-2">
        <Button asChild variant="ghost" className="hidden sm:inline-flex">
          <Link to="/login">Sign in</Link>
        </Button>
        <Button asChild>
          <Link to="/signup">Start building</Link>
        </Button>
      </div>
    </nav>
  );
}

function Hero() {
  return (
    <section className="relative mx-auto grid w-full max-w-7xl gap-10 px-4 pb-24 pt-10 lg:grid-cols-[1.05fr_1fr] lg:gap-14 lg:px-8 lg:pt-16">
      <motion.div initial="hidden" animate="show" variants={stagger} className="flex flex-col justify-center">
        <motion.div
          variants={fadeUp}
          className="inline-flex w-fit items-center gap-2 rounded-full border border-cyan/30 bg-cyan/10 px-3 py-1.5 text-xs font-medium text-cyan backdrop-blur"
        >
          <StatusPulse className="scale-75" />
          Live collaborative AI workspace
        </motion.div>

        <motion.h1
          variants={fadeUp}
          className="mt-6 text-5xl font-semibold leading-[1.05] tracking-tight text-zinc-50 md:text-6xl lg:text-7xl"
        >
          Code together.{" "}
          <span className="bg-gradient-to-r from-cyan via-signature to-violet bg-clip-text text-transparent">
            Ask your project.
          </span>{" "}
          Ship faster.
        </motion.h1>

        <motion.p variants={fadeUp} className="mt-6 max-w-xl text-base leading-7 text-zinc-400 md:text-lg">
          Codeleon is a private coding room where teammates write together in real time,
          run code in a safe sandbox, and ask a local AI questions about <em>your</em> files —
          not the whole internet.
        </motion.p>

        <motion.div variants={fadeUp} className="mt-8 flex flex-col gap-3 sm:flex-row">
          <Button asChild className="h-11 px-5">
            <Link to="/signup">
              Create workspace
              <ArrowRight className="h-4 w-4" />
            </Link>
          </Button>
          <Button asChild className="h-11 px-5" variant="secondary">
            <Link to="/login">
              <Github className="h-4 w-4" />
              Open dashboard
            </Link>
          </Button>
        </motion.div>

        <motion.div variants={fadeUp} className="mt-10 grid max-w-xl gap-3 sm:grid-cols-3">
          <Signal icon={<Radio className="h-4 w-4" />} title="Live" text="multi-cursor rooms" />
          <Signal icon={<Bot className="h-4 w-4" />} title="Private" text="local AI context" />
          <Signal icon={<ShieldCheck className="h-4 w-4" />} title="Safe" text="sandboxed runner" />
        </motion.div>
      </motion.div>

      <LiveEditor />
    </section>
  );
}

function LiveEditor() {
  const reduceMotion = useReducedMotion();
  const fullText = useMemo(() => typedLines.join("\n"), []);
  const [typed, setTyped] = useState(reduceMotion ? fullText : "");

  useEffect(() => {
    if (reduceMotion) return;
    let i = 0;
    let timer: number;
    const tick = () => {
      i = (i + 1) % (fullText.length + 60);
      setTyped(fullText.slice(0, Math.min(i, fullText.length)));
      const delay = i >= fullText.length ? 60 : 35 + Math.random() * 35;
      timer = window.setTimeout(tick, delay);
    };
    timer = window.setTimeout(tick, 400);
    return () => window.clearTimeout(timer);
  }, [fullText, reduceMotion]);

  const lines = typed.split("\n");

  return (
    <motion.div
      initial={{ opacity: 0, y: 20, rotate: -1.5 }}
      animate={{ opacity: 1, y: 0, rotate: -1.5 }}
      transition={{ duration: 0.7, ease: [0.16, 1, 0.3, 1] }}
      className="relative w-full self-center"
    >
      <div className="overflow-hidden rounded-xl border border-zinc-800/80 bg-zinc-950/85 shadow-[0_30px_120px_rgba(99,102,241,0.18)] backdrop-blur-xl">
        <div className="flex items-center justify-between border-b border-zinc-800 px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="h-3 w-3 rounded-full bg-danger" />
            <span className="h-3 w-3 rounded-full bg-warning" />
            <span className="h-3 w-3 rounded-full bg-success" />
            <span className="ml-3 font-mono text-[11px] text-zinc-500">codeleon://room/pfe-demo</span>
          </div>
          <div className="inline-flex items-center gap-1.5 rounded-md border border-emerald-800/60 bg-emerald-950/40 px-2 py-1 text-[11px] text-emerald-300">
            <CheckCircle2 className="h-3 w-3" />
            indexed
          </div>
        </div>

        <div className="grid grid-cols-[minmax(0,1fr)_11rem]">
          <div className="relative min-h-[18rem] p-5 font-mono text-[13px] leading-7 text-zinc-300">
            {lines.map((line, idx) => (
              <div key={idx} className="flex">
                <span className="mr-4 w-5 select-none text-right text-zinc-700">{idx + 1}</span>
                <span className="whitespace-pre">
                  {highlight(line)}
                  {idx === lines.length - 1 && !reduceMotion && (
                    <span className="ml-0.5 inline-block h-4 w-1.5 translate-y-0.5 animate-pulse bg-cyan" />
                  )}
                </span>
              </div>
            ))}

            <motion.div
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: typed.length > fullText.length - 20 ? 1 : 0, y: 0 }}
              transition={{ duration: 0.4 }}
              className="mt-5 rounded-md border border-zinc-800 bg-background/95 px-3 py-2"
            >
              <div className="mb-1 flex items-center gap-2 text-[11px] text-zinc-400">
                <Terminal className="h-3 w-3 text-cyan" />
                sandbox
                <span className="ml-auto text-emerald-400">exit 0</span>
              </div>
              <p className="font-mono text-[11px] text-zinc-300">8 tests passed in 0.42s</p>
            </motion.div>
          </div>

          <div className="border-l border-zinc-800 bg-surface/50 p-3">
            <div className="mb-3 flex items-center gap-1.5 text-xs font-medium text-zinc-200">
              <Lock className="h-3.5 w-3.5 text-cyan" />
              Room signal
            </div>
            <div className="space-y-2">
              {events.map((event, idx) => (
                <motion.div
                  key={event.text}
                  initial={{ opacity: 0, x: 8 }}
                  animate={{ opacity: 1, x: 0 }}
                  transition={{ delay: 0.6 + idx * 0.18 }}
                  className="rounded-md border border-zinc-800 bg-zinc-950 px-2 py-1.5 text-[11px] text-zinc-400"
                >
                  <span className={`mr-1.5 inline-flex align-middle ${event.tone}`}>{event.icon}</span>
                  {event.text}
                </motion.div>
              ))}
            </div>
          </div>
        </div>
      </div>

      <FloatingCursor label="Badr" color="#06B6D4" top="14%" left="38%" delay={0.8} />
      <FloatingCursor label="AI" color="#8B5CF6" top="58%" left="22%" delay={1.4} />
    </motion.div>
  );
}

function FloatingCursor({
  label,
  color,
  top,
  left,
  delay,
}: {
  label: string;
  color: string;
  top: string;
  left: string;
  delay: number;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, scale: 0.8 }}
      animate={{ opacity: 1, scale: 1, y: [0, -8, 0] }}
      transition={{ delay, duration: 4, repeat: Infinity, repeatType: "mirror", ease: "easeInOut" }}
      className="pointer-events-none absolute z-10 flex items-center gap-1"
      style={{ top, left }}
    >
      <svg width="14" height="16" viewBox="0 0 14 16" fill={color}>
        <path d="M0 0 L0 13 L4 10 L7 16 L9 15 L6 9 L12 9 Z" />
      </svg>
      <span
        className="rounded-md px-1.5 py-0.5 text-[10px] font-medium text-white shadow-md"
        style={{ background: color }}
      >
        {label}
      </span>
    </motion.div>
  );
}

function highlight(line: string): ReactNode {
  const keywords = /\b(from|import|def|return|class|in|as)\b/g;
  const strings = /'[^']*'/g;
  const tokens: { type: string; value: string }[] = [];
  let rest = line;

  const matchers: { type: string; re: RegExp; cls: string }[] = [
    { type: "string", re: strings, cls: "text-emerald-300" },
    { type: "keyword", re: keywords, cls: "text-violet" },
  ];

  type Match = { start: number; end: number; cls: string; value: string };
  const matches: Match[] = [];
  for (const m of matchers) {
    let r;
    while ((r = m.re.exec(line)) !== null) {
      matches.push({ start: r.index, end: r.index + r[0].length, cls: m.cls, value: r[0] });
    }
  }
  matches.sort((a, b) => a.start - b.start);

  const out: ReactNode[] = [];
  let cursor = 0;
  for (const m of matches) {
    if (m.start < cursor) continue;
    if (m.start > cursor) out.push(<span key={cursor}>{line.slice(cursor, m.start)}</span>);
    out.push(
      <span key={m.start} className={m.cls}>
        {m.value}
      </span>,
    );
    cursor = m.end;
  }
  if (cursor < line.length) out.push(<span key={`tail-${cursor}`}>{line.slice(cursor)}</span>);
  void rest;
  return out;
}

function HowItWorks() {
  const steps = [
    {
      icon: <GitBranch className="h-5 w-5" />,
      title: "Import a project",
      text: "Pull a repo or upload a folder. Codeleon parses it, indexes it, and prepares a private room.",
    },
    {
      icon: <Users className="h-5 w-5" />,
      title: "Invite teammates",
      text: "Share a link. Everyone gets live cursors, presence, and a shared terminal — no setup.",
    },
    {
      icon: <Wand2 className="h-5 w-5" />,
      title: "Ask your code, run it",
      text: "The local AI answers from your files only. The sandbox runs your code in seconds.",
    },
  ];

  return (
    <section id="how" className="mx-auto w-full max-w-7xl px-4 py-24 lg:px-8">
      <div className="mb-12 max-w-2xl">
        <p className="text-sm font-medium uppercase tracking-wider text-cyan">How it works</p>
        <h2 className="mt-3 text-3xl font-semibold text-zinc-50 md:text-4xl">
          From repo to running tests in three steps.
        </h2>
      </div>

      <div className="grid gap-5 md:grid-cols-3">
        {steps.map((s, idx) => (
          <motion.div
            key={s.title}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true, amount: 0.3 }}
            transition={{ duration: 0.5, delay: idx * 0.1 }}
            className="relative rounded-xl border border-zinc-800/80 bg-zinc-950/60 p-6 backdrop-blur transition hover:border-cyan/40"
          >
            <div className="absolute right-5 top-5 font-mono text-xs text-zinc-700">0{idx + 1}</div>
            <div className="inline-flex h-10 w-10 items-center justify-center rounded-lg border border-cyan/30 bg-cyan/10 text-cyan">
              {s.icon}
            </div>
            <h3 className="mt-4 text-lg font-semibold text-zinc-100">{s.title}</h3>
            <p className="mt-2 text-sm leading-6 text-zinc-400">{s.text}</p>
          </motion.div>
        ))}
      </div>
    </section>
  );
}

function BentoGrid() {
  return (
    <section id="features" className="mx-auto w-full max-w-7xl px-4 py-16 lg:px-8">
      <div className="mb-12 max-w-2xl">
        <p className="text-sm font-medium uppercase tracking-wider text-violet">What's inside</p>
        <h2 className="mt-3 text-3xl font-semibold text-zinc-50 md:text-4xl">
          A workspace that feels alive.
        </h2>
        <p className="mt-3 text-base text-zinc-400">
          Real-time collaboration, a sandbox that actually runs your tests, and an AI that only knows
          what you teach it.
        </p>
      </div>

      <div className="grid auto-rows-[12rem] grid-cols-1 gap-4 md:grid-cols-3 md:grid-rows-3">
        <BentoCard
          className="md:col-span-2 md:row-span-2"
          icon={<Bot className="h-5 w-5" />}
          tag="Local AI"
          title="Ask your project, not the internet"
          text="A RAG pipeline runs on your machine. The AI cites the exact file and line, and never leaks your code to a third party."
        >
          <FakeChat />
        </BentoCard>

        <BentoCard
          icon={<Radio className="h-5 w-5" />}
          tag="Realtime"
          title="Multi-cursor rooms"
          text="See teammates type, select, and comment as it happens."
        />

        <BentoCard
          icon={<Cpu className="h-5 w-5" />}
          tag="Sandbox"
          title="Run code in isolation"
          text="Docker-backed runners with strict CPU and memory limits."
        />

        <BentoCard
          icon={<Boxes className="h-5 w-5" />}
          tag="Import"
          title="GitHub & ZIP imports"
          text="Bring an existing project. Codeleon indexes it in seconds."
        />

        <BentoCard
          className="md:col-span-2"
          icon={<ShieldCheck className="h-5 w-5" />}
          tag="Privacy first"
          title="Your code never leaves your machine"
          text="Models run locally via Ollama. Rooms are end-to-end inside your network."
        >
          <div className="mt-3 flex flex-wrap gap-2">
            {["Ollama", "nomic-embed", "Spring Boot", "React", "Docker"].map((s) => (
              <span key={s} className="rounded-md border border-zinc-800 bg-background/60 px-2 py-1 font-mono text-[11px] text-zinc-400">
                {s}
              </span>
            ))}
          </div>
        </BentoCard>
      </div>
    </section>
  );
}

function BentoCard({
  className = "",
  icon,
  tag,
  title,
  text,
  children,
}: {
  className?: string;
  icon: ReactNode;
  tag: string;
  title: string;
  text: string;
  children?: ReactNode;
}) {
  return (
    <motion.div
      initial={{ opacity: 0, y: 18 }}
      whileInView={{ opacity: 1, y: 0 }}
      viewport={{ once: true, amount: 0.2 }}
      transition={{ duration: 0.45 }}
      className={`group relative flex flex-col justify-between overflow-hidden rounded-xl border border-zinc-800/80 bg-zinc-950/60 p-5 backdrop-blur transition hover:border-signature/50 hover:bg-zinc-950/80 ${className}`}
    >
      <div className="absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-cyan/60 to-transparent opacity-0 transition-opacity group-hover:opacity-100" />
      <div>
        <div className="flex items-center gap-2 text-xs font-medium text-cyan">
          <span className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-cyan/30 bg-cyan/10">
            {icon}
          </span>
          {tag}
        </div>
        <h3 className="mt-3 text-lg font-semibold text-zinc-100">{title}</h3>
        <p className="mt-1 text-sm leading-6 text-zinc-400">{text}</p>
      </div>
      {children && <div className="mt-4">{children}</div>}
    </motion.div>
  );
}

function FakeChat() {
  return (
    <div className="mt-3 space-y-2 rounded-lg border border-zinc-800 bg-background/70 p-3">
      <div className="flex items-start gap-2">
        <div className="mt-0.5 inline-flex h-5 w-5 items-center justify-center rounded-full bg-cyan/15 text-[10px] font-bold text-cyan">
          You
        </div>
        <p className="text-xs leading-5 text-zinc-300">Why does <code className="rounded bg-zinc-800 px-1 font-mono text-[11px]">test_fib</code> fail at n=0?</p>
      </div>
      <div className="flex items-start gap-2">
        <div className="mt-0.5 inline-flex h-5 w-5 items-center justify-center rounded-full bg-violet/20 text-[10px] font-bold text-violet">
          AI
        </div>
        <p className="text-xs leading-5 text-zinc-300">
          The base case in <code className="rounded bg-zinc-800 px-1 font-mono text-[11px]">fib.py:7</code> returns
          {" "}<code className="rounded bg-zinc-800 px-1 font-mono text-[11px]">1</code>, but your test expects{" "}
          <code className="rounded bg-zinc-800 px-1 font-mono text-[11px]">0</code>. Fix the base case.
          <span className="ml-1 inline-flex items-center gap-1 rounded border border-emerald-800/60 bg-emerald-950/40 px-1.5 py-0.5 text-[10px] text-emerald-300">
            <Sparkles className="h-2.5 w-2.5" />
            fib.py:7
          </span>
        </p>
      </div>
    </div>
  );
}

function FinalCta() {
  return (
    <section id="cta" className="relative mx-auto w-full max-w-7xl px-4 py-24 lg:px-8">
      <motion.div
        initial={{ opacity: 0, y: 30 }}
        whileInView={{ opacity: 1, y: 0 }}
        viewport={{ once: true, amount: 0.3 }}
        transition={{ duration: 0.6 }}
        className="relative overflow-hidden rounded-2xl border border-zinc-800/80 bg-gradient-to-br from-zinc-950 via-zinc-950 to-surface px-6 py-16 text-center shadow-glow"
      >
        <div
          aria-hidden
          className="absolute inset-0 opacity-60"
          style={{
            background:
              "radial-gradient(ellipse at top, rgba(99,102,241,0.25), transparent 60%), radial-gradient(ellipse at bottom right, rgba(6,182,212,0.20), transparent 60%)",
          }}
        />
        <div className="relative">
          <div className="inline-flex items-center gap-2 rounded-full border border-zinc-700 bg-background/60 px-3 py-1 text-xs text-zinc-300">
            <Zap className="h-3 w-3 text-cyan" />
            Ready in under a minute
          </div>
          <h2 className="mx-auto mt-5 max-w-2xl text-4xl font-semibold leading-tight text-zinc-50 md:text-5xl">
            Open a room. Bring a friend. Ship the feature.
          </h2>
          <p className="mx-auto mt-4 max-w-xl text-base text-zinc-400">
            Free while in beta. No card. Your code never leaves your network.
          </p>
          <div className="mt-8 flex flex-col items-center justify-center gap-3 sm:flex-row">
            <Button asChild className="h-11 px-6">
              <Link to="/signup">
                Create workspace
                <ArrowRight className="h-4 w-4" />
              </Link>
            </Button>
            <Button asChild className="h-11 px-6" variant="secondary">
              <Link to="/login">Sign in</Link>
            </Button>
          </div>
        </div>
      </motion.div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="border-t border-zinc-800/60 px-4 py-8 lg:px-8">
      <div className="mx-auto flex w-full max-w-7xl flex-col items-center justify-between gap-3 text-xs text-zinc-500 sm:flex-row">
        <div className="flex items-center gap-2">
          <Logo size={20} />
          <span>Codeleon · PFE 2026</span>
        </div>
        <div>Built in Fès · Java · Spring Boot · React · Ollama</div>
      </div>
    </footer>
  );
}

function Signal({ icon, title, text }: { icon: ReactNode; title: string; text: string }) {
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
