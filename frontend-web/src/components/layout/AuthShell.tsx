import { motion } from "framer-motion";
import { Bot, CheckCircle2, Radio, ShieldCheck, Terminal } from "lucide-react";
import { Link } from "react-router-dom";
import { ReactNode } from "react";
import { AnimatedBackdrop } from "@/components/brand/AnimatedBackdrop";
import { Logo } from "@/components/brand/Logo";
import { StatusPulse, fadeUp, stagger } from "@/components/ui/motion";

interface AuthShellProps {
  title: string;
  subtitle: string;
  switchLabel: string;
  switchTo: string;
  switchText: string;
  children: ReactNode;
}

export function AuthShell({
  title,
  subtitle,
  switchLabel,
  switchTo,
  switchText,
  children,
}: AuthShellProps) {
  return (
    <main className="relative min-h-screen overflow-hidden px-4 py-8">
      {/* Showcase variant: orbs at full intensity — auth pages are the
          first impression of Codeleon and deserve the "wow" backdrop. */}
      <AnimatedBackdrop variant="showcase" />
      <div className="codeleon-grid pointer-events-none absolute inset-0 z-[1] opacity-60" />
      <div className="pointer-events-none absolute inset-0 z-[1] bg-[linear-gradient(105deg,#09090b_0%,rgba(9,9,11,0.86)_44%,rgba(24,24,27,0.58)_100%)]" />

      <motion.section
        initial="hidden"
        animate="show"
        variants={stagger}
        className="relative z-10 mx-auto grid min-h-[calc(100vh-4rem)] w-full max-w-6xl items-center gap-8 lg:grid-cols-[1fr_28rem]"
      >
        <motion.div variants={fadeUp} className="hidden lg:block">
          <Link to="/" className="mb-12 flex items-center gap-3">
            <Logo size={44} />
            <span className="font-semibold text-zinc-50">Codeleon</span>
          </Link>

          <div className="max-w-xl">
            <div className="inline-flex items-center gap-2 rounded-md border border-cyan/30 bg-cyan/10 px-3 py-2 text-sm text-cyan">
              <StatusPulse className="scale-75" />
              Secure workspace access
            </div>
            <h1 className="mt-6 text-4xl font-semibold leading-tight text-zinc-50">
              Enter a room built for private collaborative coding.
            </h1>
            <p className="mt-4 leading-7 text-zinc-400">
              Your projects, invites, local AI context, and sandboxed runs stay organized behind one focused account.
            </p>
          </div>

          <div className="mt-10 grid max-w-xl gap-3">
            <AuthSignal icon={<Radio className="h-4 w-4" />} title="Live rooms" text="Presence and active files stay visible." />
            <AuthSignal icon={<Bot className="h-4 w-4" />} title="Private AI" text="Project-aware help without a hosted coding agent." />
            <AuthSignal icon={<ShieldCheck className="h-4 w-4" />} title="Safe execution" text="Run code in the sandbox, not on your host." />
          </div>
        </motion.div>

        <motion.div variants={fadeUp} className="w-full">
          <section className="relative overflow-hidden rounded-lg border border-zinc-800/80 bg-zinc-950/82 p-6 shadow-[0_24px_80px_rgba(0,0,0,0.46)] backdrop-blur-xl">
            <div className="pointer-events-none absolute inset-x-0 top-0 h-px bg-gradient-to-r from-transparent via-cyan/70 to-transparent" />
            <Link to="/" className="mb-8 flex items-center gap-3 lg:hidden">
              <Logo size={40} />
              <span className="font-semibold text-zinc-50">Codeleon</span>
            </Link>

            <div className="mb-6 space-y-2">
              <div className="mb-3 inline-flex items-center gap-2 rounded-md border border-zinc-800 bg-background px-2 py-1 text-[11px] text-zinc-500">
                <Terminal className="h-3 w-3 text-cyan" />
                account/session
              </div>
              <h1 className="text-2xl font-semibold text-zinc-50">{title}</h1>
              <p className="text-sm leading-6 text-zinc-400">{subtitle}</p>
            </div>

            {children}

            <p className="mt-6 text-center text-sm text-zinc-400">
              {switchText}{" "}
              <Link className="font-medium text-cyan hover:text-cyan/80" to={switchTo}>
                {switchLabel}
              </Link>
            </p>
          </section>
        </motion.div>
      </motion.section>
    </main>
  );
}

function AuthSignal({ icon, title, text }: { icon: JSX.Element; title: string; text: string }) {
  return (
    <div className="flex items-center gap-3 rounded-md border border-zinc-800 bg-zinc-950/60 px-4 py-3 backdrop-blur">
      <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md border border-cyan/30 bg-cyan/10 text-cyan">
        {icon}
      </div>
      <div>
        <div className="flex items-center gap-2 text-sm font-medium text-zinc-100">
          {title}
          <CheckCircle2 className="h-3.5 w-3.5 text-emerald-300" />
        </div>
        <p className="mt-0.5 text-xs text-zinc-500">{text}</p>
      </div>
    </div>
  );
}
