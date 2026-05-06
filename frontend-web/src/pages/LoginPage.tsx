import { useSearchParams } from "react-router-dom";
import { LoginForm } from "@/components/auth/LoginForm";
import { OAuthButtons } from "@/components/auth/OAuthButtons";
import { AuthShell } from "@/components/layout/AuthShell";

export function LoginPage() {
  const [params] = useSearchParams();
  const oauthError = params.get("oauth_error");

  return (
    <AuthShell
      title="Welcome back"
      subtitle="Sign in to continue building collaborative rooms."
      switchText="New to Codeleon?"
      switchLabel="Create an account"
      switchTo="/signup"
    >
      {oauthError && (
        <div className="mb-4 rounded-md border border-rose-900 bg-rose-950/40 px-3 py-2 text-xs text-rose-300">
          {describeOAuthError(oauthError)}
        </div>
      )}

      <OAuthButtons action="Sign in" />
      <LoginForm />
    </AuthShell>
  );
}

function describeOAuthError(code: string): string {
  switch (code) {
    case "oauth_link_conflict":
      return "An account already exists with this email. Sign in with your password first to link your social account.";
    case "oauth_profile_extraction_failed":
      return "We could not read your profile from the provider. Please try again.";
    case "oauth_unexpected_principal":
    case "callback_failed":
      return "Sign-in failed mid-way. Please retry.";
    default:
      return "Social sign-in failed. Please try again.";
  }
}
