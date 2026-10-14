import { PASSWORD_MAX_LENGTH, PASSWORD_MIN_LENGTH } from "@/lib/api/auth";

/**
 * Client-side checks for a new password, mirroring the backend's length rule.
 * The backend remains authoritative and applies further checks.
 * Returns field errors keyed by "password" / "confirm", or null if valid.
 */
export function validateNewPassword(password: string, confirm: string, field = "password"): Record<string, string> | null {
  if (password.length < PASSWORD_MIN_LENGTH) {
    return { [field]: `Use at least ${PASSWORD_MIN_LENGTH} characters` };
  }
  if (password.length > PASSWORD_MAX_LENGTH) {
    return { [field]: `Use at most ${PASSWORD_MAX_LENGTH} characters` };
  }
  if (password !== confirm) {
    return { confirm: "Passwords do not match" };
  }
  return null;
}
