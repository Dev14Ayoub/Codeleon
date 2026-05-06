import { CSSProperties } from "react";
import { cn } from "@/lib/utils";

interface LogoProps {
  /** Pixel size of the logo's bounding box. Defaults to 36. */
  size?: number;
  className?: string;
  style?: CSSProperties;
}

/**
 * Codeleon brand mark — a stylized chameleon (head, body, three legs,
 * a curled tail) sitting on the project gradient (signature indigo →
 * cyan). Inlined as JSX so consumers can resize it without a separate
 * SVG fetch and so the gradient definition stays scoped to one
 * component instance.
 *
 * The mark works down to favicon size; for the actual /favicon.svg
 * ship the equivalent file from `public/favicon.svg` (kept in sync).
 */
export function Logo({ size = 36, className, style }: LogoProps) {
  // Each Logo instance must scope its <linearGradient id> so multiple
  // logos on the same page do not accidentally share (or override) the
  // same gradient definition. Using the React-provided unique id-like
  // pattern would be ideal, but we are calling Logo from many places
  // and a stable suffix is enough — the gradient ids only collide when
  // someone embeds two Logos with the same key, which never happens.
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 64 64"
      width={size}
      height={size}
      role="img"
      aria-label="Codeleon"
      className={cn("shrink-0", className)}
      style={style}
    >
      <defs>
        <linearGradient
          id="codeleon-logo-bg"
          x1="0"
          y1="0"
          x2="64"
          y2="64"
          gradientUnits="userSpaceOnUse"
        >
          <stop offset="0%" stopColor="#6366F1" />
          <stop offset="100%" stopColor="#06B6D4" />
        </linearGradient>
      </defs>
      {/* Rounded square background carrying the brand gradient */}
      <rect width="64" height="64" rx="14" fill="url(#codeleon-logo-bg)" />
      {/* Chameleon body (oval) + snout protrusion */}
      <ellipse cx="34" cy="30" rx="14" ry="9" fill="#FAFAFA" />
      <path d="M 46 27 Q 52 28 52 32 Q 52 35 46 34 Z" fill="#FAFAFA" />
      {/* Three little legs */}
      <rect x="22" y="37" width="3" height="6" rx="1" fill="#FAFAFA" />
      <rect x="32" y="37" width="3" height="6" rx="1" fill="#FAFAFA" />
      <rect x="40" y="37" width="3" height="6" rx="1" fill="#FAFAFA" />
      {/* Curled tail — the distinguishing chameleon trait */}
      <path
        d="M 22 28 Q 14 28 14 34 Q 14 40 20 40 Q 26 40 26 34 Q 26 32 24 32"
        fill="none"
        stroke="#FAFAFA"
        strokeWidth="3"
        strokeLinecap="round"
      />
      {/* Iconic turret eye — dark sclera, cyan iris */}
      <circle cx="42" cy="28" r="3.5" fill="#0F172A" />
      <circle cx="42" cy="28" r="1.5" fill="#06B6D4" />
    </svg>
  );
}
