import { Navigate, Outlet } from "react-router-dom";
import { useAuthStore } from "@/stores/auth-store";

/**
 * Route guard for /admin/*. Bounces unauthenticated users to /login,
 * and bounces authenticated non-admins to /dashboard. The backend
 * also enforces this with hasRole("ADMIN") on /admin/** so a curl
 * with a USER-role JWT still 403s, but the client-side guard saves a
 * round-trip and shows a sensible page.
 */
export function AdminRoute() {
  const { accessToken, user } = useAuthStore();

  if (!accessToken) {
    return <Navigate to="/login" replace />;
  }

  if (user?.role !== "ADMIN") {
    return <Navigate to="/dashboard" replace />;
  }

  return <Outlet />;
}
