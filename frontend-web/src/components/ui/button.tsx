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
      primary: "bg-signature text-white shadow-[0_10px_30px_rgba(99,102,241,0.22)] hover:bg-indigo-500 hover:shadow-[0_14px_36px_rgba(99,102,241,0.32)]",
      secondary: "border border-zinc-700 bg-surface text-zinc-100 hover:border-zinc-600 hover:bg-surfaceRaised",
      ghost: "text-zinc-300 hover:bg-surface hover:text-white",
    };

    return (
      <Component
        ref={ref}
        className={cn(
          // Denser default: h-9 (was h-10), px-3.5 (was px-4), gap-1.5
          // (was gap-2). Smaller hover lift (-translate-y-px) so dense
          // toolbars don't visually jitter on hover.
          "inline-flex h-9 items-center justify-center gap-1.5 rounded-md px-3.5 text-sm font-medium transition duration-200 hover:-translate-y-px active:translate-y-0 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:translate-y-0",
          variants[variant],
          className,
        )}
        {...props}
      />
    );
  },
);

Button.displayName = "Button";
