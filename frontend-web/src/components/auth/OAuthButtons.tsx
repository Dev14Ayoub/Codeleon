import { useQuery } from "@tanstack/react-query";
import { Github } from "lucide-react";
import { API_BASE_URL, fetchOAuthProviders } from "@/lib/api";
import { cn } from "@/lib/utils";

interface OAuthButtonsProps {
  /**
   * Verb used in the button label. "Sign in" on the login page,
   * "Sign up" on the signup page — both flows hit the same backend
   * endpoint, the wording just clarifies intent for the user.
   */
  action?: "Sign in" | "Continue";
}

const providerCopy: Record<string, { label: string; icon: JSX.Element; className: string }> = {
  github: {
    label: "GitHub",
    icon: <Github className="h-4 w-4" />,
    className:
      "border border-zinc-700 bg-zinc-950 text-zinc-100 hover:bg-zinc-900 hover:border-zinc-600",
  },
  google: {
    label: "Google",
    // Inline Google "G" mark — keeps the dependency footprint flat.
    icon: <GoogleMark />,
    className:
      "border border-zinc-200 bg-white text-zinc-900 hover:bg-zinc-100",
  },
};

export function OAuthButtons({ action = "Continue" }: OAuthButtonsProps) {
  const query = useQuery({
    queryKey: ["auth-providers"],
    queryFn: fetchOAuthProviders,
    staleTime: 60_000,
  });

  const providers = query.data?.providers ?? [];

  if (query.isLoading) {
    return (
      <div className="space-y-2">
        <div className="h-10 animate-pulse rounded-md bg-zinc-900" />
        <div className="h-10 animate-pulse rounded-md bg-zinc-900" />
      </div>
    );
  }

  if (providers.length === 0) {
    // No providers configured — render nothing so the page doesn't show
    // a half-broken "or" divider with empty space below it.
    return null;
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2">
        {providers.map((id) => {
          const meta = providerCopy[id];
          if (!meta) return null;
          return (
            <a
              key={id}
              href={`${API_BASE_URL}/oauth2/authorization/${id}`}
              className={cn(
                "inline-flex h-10 w-full items-center justify-center gap-2 rounded-md text-sm font-medium transition",
                meta.className,
              )}
            >
              {meta.icon}
              {action} with {meta.label}
            </a>
          );
        })}
      </div>

      <div className="flex items-center gap-3">
        <hr className="flex-1 border-zinc-800" />
        <span className="text-[10px] uppercase tracking-wide text-zinc-500">or</span>
        <hr className="flex-1 border-zinc-800" />
      </div>
    </div>
  );
}

function GoogleMark() {
  return (
    <svg className="h-4 w-4" viewBox="0 0 48 48" aria-hidden>
      <path
        fill="#FFC107"
        d="M43.611 20.083H42V20H24v8h11.303c-1.649 4.657-6.08 8-11.303 8-6.627 0-12-5.373-12-12s5.373-12 12-12c3.059 0 5.842 1.154 7.961 3.039l5.657-5.657C34.046 6.053 29.268 4 24 4 12.955 4 4 12.955 4 24s8.955 20 20 20 20-8.955 20-20c0-1.341-.138-2.65-.389-3.917z"
      />
      <path
        fill="#FF3D00"
        d="M6.306 14.691l6.571 4.819C14.655 15.108 18.961 12 24 12c3.059 0 5.842 1.154 7.961 3.039l5.657-5.657C34.046 6.053 29.268 4 24 4 16.318 4 9.656 8.337 6.306 14.691z"
      />
      <path
        fill="#4CAF50"
        d="M24 44c5.166 0 9.86-1.977 13.409-5.192l-6.19-5.238C29.211 35.091 26.715 36 24 36c-5.202 0-9.619-3.317-11.283-7.946l-6.522 5.025C9.505 39.556 16.227 44 24 44z"
      />
      <path
        fill="#1976D2"
        d="M43.611 20.083H42V20H24v8h11.303c-.792 2.237-2.231 4.166-4.087 5.571.001-.001.002-.001.003-.002l6.19 5.238C36.971 39.205 44 34 44 24c0-1.341-.138-2.65-.389-3.917z"
      />
    </svg>
  );
}
