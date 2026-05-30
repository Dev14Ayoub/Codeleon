import { cn } from "@/lib/utils";

interface AnimatedBackdropProps {
  /**
   * Visual intensity of the orbs.
   *
   * - "subtle"   — opacity ~ 0.18, used on dense pages (dashboard, admin)
   *                where the orbs should hint at depth without competing
   *                with content.
   * - "showcase" — opacity ~ 0.35, used on marketing / auth pages where
   *                the orbs ARE the visual story.
   */
  variant?: "subtle" | "showcase";
  className?: string;
}

/**
 * Fullscreen, fixed-positioned animated backdrop. Three blurred radial
 * gradient orbs (indigo, cyan, violet) drift slowly across the viewport;
 * a vignette darkens the edges. Pure CSS — no canvas, no JS animation
 * loop, no framer-motion. GPU-accelerated via transform-only keyframes.
 *
 * <p>Honors {@code prefers-reduced-motion}: the global CSS rule in
 * globals.css zeroes the animation duration so the orbs sit still
 * rather than vanish — depth without motion.
 *
 * <p>The component renders at z-index 0; place page content inside a
 * container with {@code position: relative} and {@code z-10} so it
 * stacks above the orbs.
 */
export function AnimatedBackdrop({ variant = "subtle", className }: AnimatedBackdropProps) {
  return (
    <div
      aria-hidden
      className={cn(
        "codeleon-backdrop",
        variant === "subtle" && "codeleon-backdrop--subtle",
        className,
      )}
    >
      <span className="codeleon-orb codeleon-orb--indigo" />
      <span className="codeleon-orb codeleon-orb--cyan" />
      <span className="codeleon-orb codeleon-orb--violet" />
    </div>
  );
}
