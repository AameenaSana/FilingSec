import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { LoginForm } from "./LoginForm";

const replace = vi.fn();
let search = new URLSearchParams();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace }),
  useSearchParams: () => search,
}));

const setUser = vi.fn();
vi.mock("@/lib/auth/AuthProvider", () => ({ useAuth: () => ({ setUser }) }));

const login = vi.fn();
const resendVerification = vi.fn();
vi.mock("@/lib/api/auth", () => ({
  authApi: {
    login: (...args: unknown[]) => login(...args),
    resendVerification: (...args: unknown[]) => resendVerification(...args),
  },
}));

const account = {
  id: "1",
  email: "a@example.com",
  displayName: "Analyst",
  role: "USER",
  emailVerified: true,
  createdAt: "2026-01-01T00:00:00Z",
};

async function submit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(screen.getByLabelText("Email"), email);
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Sign in" }));
  return user;
}

describe("LoginForm", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    search = new URLSearchParams();
  });

  it("signs in and goes to the dashboard", async () => {
    login.mockResolvedValue(account);
    render(<LoginForm />);

    await submit("a@example.com", "secret passphrase");

    expect(login).toHaveBeenCalledWith("a@example.com", "secret passphrase");
    expect(setUser).toHaveBeenCalledWith(account);
    expect(replace).toHaveBeenCalledWith("/dashboard");
  });

  it("returns to a safe ?next= path but ignores external ones", async () => {
    login.mockResolvedValue(account);
    search = new URLSearchParams("next=/settings");
    const { unmount } = render(<LoginForm />);
    await submit("a@example.com", "pw");
    expect(replace).toHaveBeenLastCalledWith("/settings");
    unmount();

    search = new URLSearchParams("next=//evil.example");
    render(<LoginForm />);
    await submit("a@example.com", "pw");
    expect(replace).toHaveBeenLastCalledWith("/dashboard");
  });

  it("shows the server message and clears the password on failure", async () => {
    login.mockRejectedValue(
      new ApiError(401, { type: "urn:filinglens:problem:invalid-credentials", detail: "Invalid email or password." }),
    );
    render(<LoginForm />);

    await submit("a@example.com", "wrong");

    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password.");
    expect(screen.getByLabelText("Password")).toHaveValue("");
    expect(replace).not.toHaveBeenCalled();
  });

  it("offers to resend the verification email for unverified accounts", async () => {
    login.mockRejectedValue(
      new ApiError(403, { type: "urn:filinglens:problem:email-not-verified", detail: "Confirm your email address." }),
    );
    resendVerification.mockResolvedValue({ message: "If the address can be used, we have sent an email." });
    render(<LoginForm />);

    const user = await submit("new@example.com", "secret passphrase");
    await user.click(await screen.findByRole("button", { name: "Resend verification email" }));

    expect(resendVerification).toHaveBeenCalledWith("new@example.com");
    expect(await screen.findByText(/we have sent an email/)).toBeInTheDocument();
  });
});
