import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { LogIn } from "lucide-react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { FieldError } from "@/components/ui/field-error";
import { Input } from "@/components/ui/input";
import { loginUser } from "@/lib/api";
import { LoginValues, loginSchema } from "@/lib/validators";
import { useAuthStore } from "@/stores/auth-store";

export function LoginForm() {
  const navigate = useNavigate();
  const setSession = useAuthStore((state) => state.setSession);
  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: "", password: "" },
  });

  const mutation = useMutation({
    mutationFn: loginUser,
    onSuccess: (session) => {
      setSession(session);
      navigate("/dashboard");
    },
  });

  const serverError =
    mutation.error instanceof AxiosError
      ? mutation.error.response?.data?.message ?? "Login failed"
      : "Login failed";

  return (
    <form className="space-y-4" onSubmit={form.handleSubmit((values) => mutation.mutate(values))}>
      <div className="space-y-2">
        <label className="text-sm font-medium text-zinc-200" htmlFor="email">
          Email
        </label>
        <Input id="email" type="email" autoComplete="email" {...form.register("email")} />
        <FieldError message={form.formState.errors.email?.message} />
      </div>

      <div className="space-y-2">
        <label className="text-sm font-medium text-zinc-200" htmlFor="password">
          Password
        </label>
        <Input id="password" type="password" autoComplete="current-password" {...form.register("password")} />
        <FieldError message={form.formState.errors.password?.message} />
      </div>

      {mutation.isError && <p className="text-sm text-danger">{serverError}</p>}

      <Button className="w-full" disabled={mutation.isPending} type="submit">
        <LogIn className="h-4 w-4" />
        {mutation.isPending ? "Signing in..." : "Sign in"}
      </Button>
    </form>
  );
}
