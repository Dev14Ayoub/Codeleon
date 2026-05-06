import { Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { Navigate, useNavigate, useSearchParams } from "react-router-dom";
import { fetchCurrentUser } from "@/lib/api";
import { useAuthStore } from "@/stores/auth-store";

/**
 * Landing page hit by the OAuth2 redirect from Spring Security after a
 * successful social login. The backend appends the freshly-minted
 * accessToken + refreshToken to the URL; we extract them, fetch the
 * matching user profile, push everything into the auth store, and
 * forward to /dashboard.
 *
 * Errors come back as ?oauth_error=... and bounce to /login with the
 * code preserved so the login page can render a friendly message.
 */
export function AuthCallbackPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const setSession = useAuthStore((state) => state.setSession);

  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const accessToken = params.get("accessToken");
    const refreshToken = params.get("refreshToken");
    const oauthError = params.get("oauth_error");

    if (oauthError) {
      navigate(`/login?oauth_error=${encodeURIComponent(oauthError)}`, { replace: true });
      return;
    }

    if (!accessToken || !refreshToken) {
      setError("Missing tokens in OAuth callback URL.");
      return;
    }

    // Push tokens into the store first so the axios interceptor picks
    // up the Authorization header on the very next request.
    useAuthStore.setState({ accessToken, refreshToken });

    fetchCurrentUser()
      .then((user) => {
        setSession({ accessToken, refreshToken, user });
        navigate("/dashboard", { replace: true });
      })
      .catch(() => {
        setError("Could not fetch your profile after sign-in.");
      });
    // We deliberately want this effect to run exactly once on mount.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (error) {
    return (
      <Navigate
        to={`/login?oauth_error=${encodeURIComponent("callback_failed")}`}
        replace
      />
    );
  }

  return (
    <main className="flex min-h-screen items-center justify-center px-4 py-10">
      <div className="flex items-center gap-3 text-sm text-zinc-300">
        <Loader2 className="h-5 w-5 animate-spin text-cyan" />
        Signing you in…
      </div>
    </main>
  );
}
