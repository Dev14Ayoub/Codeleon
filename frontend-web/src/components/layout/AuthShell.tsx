import { Link } from "react-router-dom";
import { Braces } from "lucide-react";
import { ReactNode } from "react";

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
    <main className="flex min-h-screen items-center justify-center px-4 py-10">
      <section className="w-full max-w-md rounded-lg border border-zinc-800 bg-surface/80 p-6 shadow-glow backdrop-blur">
        <Link to="/" className="mb-8 flex items-center gap-3">
          <span className="flex h-10 w-10 items-center justify-center rounded-md bg-signature text-white">
            <Braces className="h-5 w-5" />
          </span>
          <span className="font-semibold text-zinc-50">Codeleon</span>
        </Link>

        <div className="mb-6 space-y-2">
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
    </main>
  );
}
