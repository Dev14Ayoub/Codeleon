import { LoginForm } from "@/components/auth/LoginForm";
import { AuthShell } from "@/components/layout/AuthShell";

export function LoginPage() {
  return (
    <AuthShell
      title="Welcome back"
      subtitle="Sign in to continue building collaborative rooms."
      switchText="New to Codeleon?"
      switchLabel="Create an account"
      switchTo="/signup"
    >
      <LoginForm />
    </AuthShell>
  );
}
