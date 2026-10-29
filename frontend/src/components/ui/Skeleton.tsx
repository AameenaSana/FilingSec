/** Shimmering placeholder shown while content loads. */
export function Skeleton({ className = "" }: { className?: string }) {
  return (
    <div
      aria-hidden
      className={`animate-shimmer rounded-md bg-linear-to-r from-zinc-200 via-zinc-100 to-zinc-200 bg-size-[200%_100%] dark:from-zinc-800 dark:via-zinc-700/60 dark:to-zinc-800 ${className}`}
    />
  );
}
