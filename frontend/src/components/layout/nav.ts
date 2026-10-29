import type { IconName } from "@/components/ui/Icon";

export const NAV: { href: string; label: string; icon: IconName }[] = [
  { href: "/dashboard", label: "Dashboard", icon: "dashboard" },
  { href: "/companies", label: "Companies", icon: "building" },
  { href: "/watchlist", label: "Watchlist", icon: "star" },
  { href: "/research", label: "Research", icon: "chat" },
  { href: "/workspace", label: "Workspace", icon: "folder" },
  { href: "/settings", label: "Settings", icon: "settings" },
];
