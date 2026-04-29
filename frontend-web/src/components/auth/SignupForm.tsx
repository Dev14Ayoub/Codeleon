import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation } from "@tanstack/react-query";
import { AxiosError } from "axios";
import { UserPlus } from "lucide-react";
import { useForm } from "react-hook-form";
import { useNavigate } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { FieldError } from "@/components/ui/field-error";
import { Input } from "@/components/ui/input";
import { registerUser } from "@/lib/api";
import { SignupValues, signupSchema } from "@/lib/validators";
import { useAuthStore } from "@/stores/auth-store";

export function SignupForm() {
  const navigate = useNavigate();
  const setSession = useAuthStore((state) => state.setSession);
  const form = useForm<SignupValues>({
    resolver: zodResolver(signupSchema),
    defaultValues: { fullName: "", email: "", password: "" },
  });

  const mutation = useMutation({
    mutationFn: registerUser,
    onSuccess: (session) => {
      setSession(session);
      navigate("/dashboard");
    },
  });

  const serverError =
    mutation.error instanceof AxiosError
      ? mutation.error.response?.data?.message ?? "Signup failed"
      : "Signup failed";

  return (
    <form className="space-y-4" onSubmit={form.handleSubmit((values) => mutation.mutate(values))}>
      <div className="space-y-2">
        <label className="text-sm font-medium text-zinc-200" htmlFor="fullName">
          Full name
        </label>
        <Input id="fullName" autoComplete="name" {...form.register("fullName")} />
        <FieldError message={form.formState.errors.fullName?.message} />
      </div>

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
        <Input id="password" type="password" autoComplete="new-password" {...form.register("password")} />
        <FieldError message={form.formState.errors.password?.message} />
      </div>

      {mutation.isError && <p className="text-sm text-danger">{serverError}</p>}

      <Button className="w-full" disabled={mutation.isPending} type="submit">
        <UserPlus className="h-4 w-4" />
        {mutation.isPending ? "Creating account..." : "Create account"}
      </Button>
    </form>
  );
}
