"use client";

import { ChangePasswordForm } from "@/components/settings/ChangePasswordForm";
import { DeleteAccountForm } from "@/components/settings/DeleteAccountForm";
import { ProfileForm } from "@/components/settings/ProfileForm";
import { useAuth } from "@/lib/auth/AuthProvider";

export function SettingsView() {
  const { state } = useAuth();
  if (!state.user) {
    return null;
  }
  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <h1 className="text-2xl font-semibold tracking-tight">Settings</h1>
      <ProfileForm account={state.user} />
      <ChangePasswordForm />
      <DeleteAccountForm />
    </div>
  );
}
