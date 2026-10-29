import type { ButtonHTMLAttributes } from "react";
import { Spinner } from "./Icon";

type Variant = "primary" | "secondary" | "danger" | "ghost";

const variants: Record<Variant, string> = {
  primary:
    "bg-violet-600 text-white shadow-sm shadow-violet-600/20 hover:bg-violet-500 hover:shadow-md hover:shadow-violet-600/25 dark:bg-violet-500 dark:hover:bg-violet-400",
  secondary:
    "border border-zinc-300 bg-white text-zinc-900 shadow-xs hover:border-zinc-400 hover:bg-zinc-50 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:hover:border-zinc-600 dark:hover:bg-zinc-800",
  danger: "bg-red-600 text-white shadow-sm hover:bg-red-500",
  ghost: "text-zinc-700 hover:bg-zinc-100 dark:text-zinc-300 dark:hover:bg-zinc-800",
};

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  pending?: boolean;
}

export function Button({ variant = "primary", pending = false, disabled, className = "", children, ...props }: ButtonProps) {
  return (
    <button
      {...props}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      className={`inline-flex h-10 items-center justify-center gap-2 rounded-lg px-4 text-sm font-medium transition-all duration-150 select-none focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-violet-500 enabled:active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-60 ${variants[variant]} ${className}`}
    >
      {pending && <Spinner className="size-3.5" />}
      {children}
    </button>
  );
}
