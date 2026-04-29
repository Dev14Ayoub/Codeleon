import { Slot } from "@radix-ui/react-slot";
import { ButtonHTMLAttributes, forwardRef } from "react";
import { cn } from "@/lib/utils";

type ButtonVariant = "primary" | "secondary" | "ghost";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  asChild?: boolean;
  variant?: ButtonVariant;
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ asChild = false, className, variant = "primary", ...props }, ref) => {
    const Component = asChild ? Slot : "button";
    const variants: Record<ButtonVariant, string> = {
      primary: "bg-signature text-white hover:bg-indigo-500",
      secondary: "border border-zinc-700 bg-surface text-zinc-100 hover:bg-surfaceRaised",
      ghost: "text-zinc-300 hover:bg-surface",
    };

    return (
      <Component
        ref={ref}
        className={cn(
          "inline-flex h-10 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium transition disabled:cursor-not-allowed disabled:opacity-50",
          variants[variant],
          className,
        )}
        {...props}
      />
    );
  },
);

Button.displayName = "Button";
