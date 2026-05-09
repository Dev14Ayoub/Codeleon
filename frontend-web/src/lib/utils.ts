import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export function formatRelativeDate(iso: string): string {
  const target = new Date(iso).getTime();
  if (Number.isNaN(target)) {
    return "";
  }
  const diffMs = Date.now() - target;
  const diffSec = Math.round(diffMs / 1000);
  if (diffSec < 60) return "just now";
  const diffMin = Math.round(diffSec / 60);
  if (diffMin < 60) return diffMin === 1 ? "1 minute ago" : `${diffMin} minutes ago`;
  const diffHour = Math.round(diffMin / 60);
  if (diffHour < 24) return diffHour === 1 ? "1 hour ago" : `${diffHour} hours ago`;
  const diffDay = Math.round(diffHour / 24);
  if (diffDay < 7) return diffDay === 1 ? "yesterday" : `${diffDay} days ago`;
  const diffWeek = Math.round(diffDay / 7);
  if (diffWeek < 5) return diffWeek === 1 ? "1 week ago" : `${diffWeek} weeks ago`;
  const diffMonth = Math.round(diffDay / 30);
  if (diffMonth < 12) return diffMonth === 1 ? "1 month ago" : `${diffMonth} months ago`;
  const diffYear = Math.round(diffDay / 365);
  return diffYear === 1 ? "1 year ago" : `${diffYear} years ago`;
}
