import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { RegisterForm } from "./RegisterForm";

const register = vi.fn();
vi.mock("@/lib/api/auth", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api/auth")>()),
  authApi: { register: (...args: unknown[]) => register(...args) },
}));

async function fill(values: { name?: string; email?: string; password?: string; confirm?: string }) {
  const user = userEvent.setup();
  if (values.name) await user.type(screen.getByLabelText("Name"), values.name);
  if (values.email) await user.type(screen.getByLabelText("Email"), values.email);
  if (values.password) await user.type(screen.getByLabelText("Password"), values.password);
  if (values.confirm) await user.type(screen.getByLabelText("Confirm password"), values.confirm);
  await user.click(screen.getByRole("button", { name: "Create account" }));
}

describe("RegisterForm", () => {
  beforeEach(() => vi.clearAllMocks());

  it("validates password length and confirmation before calling the API", async () => {
    render(<RegisterForm />);

    await fill({ name: "Jane", email: "j@example.com", password: "short", confirm: "short" });
    expect(screen.getByText("Use at least 12 characters")).toBeInTheDocument();

    await fill({ password: "-more-characters", confirm: "x" });
    expect(screen.getByText("Passwords do not match")).toBeInTheDocument();
    expect(register).not.toHaveBeenCalled();
  });

  it("shows the neutral confirmation after submitting", async () => {
    register.mockResolvedValue({ message: "If the address can be used, we have sent an email with the next steps." });
    render(<RegisterForm />);

    await fill({
      name: "Jane",
      email: "j@example.com",
      password: "a good long passphrase",
      confirm: "a good long passphrase",
    });

    expect(register).toHaveBeenCalledWith("j@example.com", "a good long passphrase", "Jane");
    expect(await screen.findByText(/we have sent an email/)).toBeInTheDocument();
  });

  it("shows server-side field errors", async () => {
    register.mockRejectedValue(
      new ApiError(400, {
        type: "urn:filinglens:problem:weak-password",
        detail: "Password must not contain your email address",
      }),
    );
    render(<RegisterForm />);

    await fill({
      name: "Jane",
      email: "jane.doe@example.com",
      password: "jane.doe-passphrase",
      confirm: "jane.doe-passphrase",
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("Password must not contain your email address");
  });
});
