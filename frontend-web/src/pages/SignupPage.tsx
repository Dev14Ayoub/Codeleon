import { OAuthButtons } from "@/components/auth/OAuthButtons";
import { SignupForm } from "@/components/auth/SignupForm";
import { AuthShell } from "@/components/layout/AuthShell";

export function SignupPage() {
  return (
    <AuthShell
      title="Create your account"
      subtitle="Start with a secure workspace and prepare your first collaborative room."
      switchText="Already have an account?"
      switchLabel="Sign in"
      switchTo="/login"
    >
      <OAuthButtons action="Continue" />
      <SignupForm />
    </AuthShell>
  );
}
