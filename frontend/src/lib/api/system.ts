import { apiFetch } from "./client";

export interface SystemInfo {
  name: string;
  version: string;
  serverTime: string;
}

export function getSystemInfo(): Promise<SystemInfo> {
  return apiFetch<SystemInfo>("/system/info");
}
