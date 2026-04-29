import type { Config } from "tailwindcss";

export default {
  darkMode: ["class"],
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        background: "#09090B",
        surface: "#18181B",
        surfaceRaised: "#27272A",
        signature: "#6366F1",
        violet: "#8B5CF6",
        cyan: "#06B6D4",
        success: "#10B981",
        warning: "#F59E0B",
        danger: "#EF4444",
      },
      fontFamily: {
        sans: ["Geist", "Inter", "ui-sans-serif", "system-ui"],
        mono: ["Geist Mono", "ui-monospace", "SFMono-Regular", "monospace"],
      },
      boxShadow: {
        glow: "0 0 48px rgba(99, 102, 241, 0.28)",
      },
    },
  },
  plugins: [],
} satisfies Config;
