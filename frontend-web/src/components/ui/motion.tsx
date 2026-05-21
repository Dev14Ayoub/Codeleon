/* eslint-disable react-refresh/only-export-components */
import { motion, type HTMLMotionProps, type Variants } from "framer-motion";
import { type ReactNode } from "react";
import { cn } from "@/lib/utils";

export const fadeUp: Variants = {
  hidden: { opacity: 0, y: 18 },
  show: { opacity: 1, y: 0, transition: { duration: 0.45, ease: [0.16, 1, 0.3, 1] } },
};

export const popIn: Variants = {
  hidden: { opacity: 0, scale: 0.97, y: 10 },
  show: { opacity: 1, scale: 1, y: 0, transition: { duration: 0.35, ease: [0.16, 1, 0.3, 1] } },
};

export const stagger: Variants = {
  hidden: {},
  show: { transition: { staggerChildren: 0.07, delayChildren: 0.05 } },
};

interface MotionPageProps {
  children: ReactNode;
  className?: string;
}

export function MotionPage({ children, className }: MotionPageProps) {
  return (
    <motion.div
      initial="hidden"
      animate="show"
      variants={fadeUp}
      className={className}
    >
      {children}
    </motion.div>
  );
}

export function MotionCard({
  children,
  className,
  ...props
}: HTMLMotionProps<"div"> & { children: ReactNode }) {
  return (
    <motion.div
      variants={popIn}
      whileHover={{ y: -4, scale: 1.01 }}
      whileTap={{ scale: 0.99 }}
      transition={{ type: "spring", stiffness: 420, damping: 30 }}
      className={cn(
        "group relative overflow-hidden rounded-lg border border-zinc-800 bg-surface",
        "shadow-[0_14px_40px_rgba(0,0,0,0.24)] transition-colors",
        "before:absolute before:inset-x-0 before:top-0 before:h-px before:bg-gradient-to-r before:from-transparent before:via-cyan/60 before:to-transparent before:opacity-0 before:transition-opacity hover:before:opacity-100",
        "hover:border-signature/60 hover:bg-surfaceRaised/80 hover:shadow-[0_20px_60px_rgba(99,102,241,0.18)]",
        className,
      )}
      {...props}
    >
      {children}
    </motion.div>
  );
}

export function StatusPulse({ className }: { className?: string }) {
  return (
    <span className={cn("relative flex h-2.5 w-2.5", className)}>
      <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-cyan opacity-60" />
      <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-cyan" />
    </span>
  );
}
