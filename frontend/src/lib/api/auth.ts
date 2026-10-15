import { apiFetch } from "./client";

export interface Account {
  id: string;
  email: string;
  displayName: string;
  role: "USER" | "ADMIN";
  emailVerified: boolean;
  createdAt: string;
}

export interface Acknowledgement {
  message: string;
}

const json = (body: unknown) => JSON.stringify(body);

export const authApi = {
  me: () => apiFetch<Account>("/auth/me"),

  login: (email: string, password: string) =>
    apiFetch<Account>("/auth/login", { method: "POST", body: json({ email, password }) }),

  /** Exchanges a Supabase access token (e.g. after Google sign-in) for a FilingLens session. */
  supabaseSignIn: (accessToken: string) =>
    apiFetch<Account>("/auth/supabase", { method: "POST", body: json({ accessToken }) }),

  logout: () => apiFetch<void>("/auth/logout", { method: "POST" }),

  register: (email: string, password: string, displayName: string) =>
    apiFetch<Acknowledgement>("/auth/register", { method: "POST", body: json({ email, password, displayName }) }),

  verifyEmail: (token: string) => apiFetch<void>("/auth/verify-email", { method: "POST", body: json({ token }) }),

  resendVerification: (email: string) =>
    apiFetch<Acknowledgement>("/auth/verify-email/resend", { method: "POST", body: json({ email }) }),

  forgotPassword: (email: string) =>
    apiFetch<Acknowledgement>("/auth/password/forgot", { method: "POST", body: json({ email }) }),

  resetPassword: (token: string, newPassword: string) =>
    apiFetch<void>("/auth/password/reset", { method: "POST", body: json({ token, newPassword }) }),
};

export const accountApi = {
  update: (displayName: string) => apiFetch<Account>("/account", { method: "PATCH", body: json({ displayName }) }),

  changePassword: (currentPassword: string, newPassword: string) =>
    apiFetch<void>("/account/password", { method: "PUT", body: json({ currentPassword, newPassword }) }),

  delete: (password: string) => apiFetch<void>("/account", { method: "DELETE", body: json({ password }) }),
};

export const PASSWORD_MIN_LENGTH = 12;
export const PASSWORD_MAX_LENGTH = 128;
