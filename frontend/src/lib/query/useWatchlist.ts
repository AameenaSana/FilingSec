"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { queryKeys, watchlistApi, type FormType, type WatchlistEntry } from "@/lib/api/companies";

/** The signed-in user's watchlist, shared by every view that shows watch status. */
export function useWatchlist() {
  return useQuery({ queryKey: queryKeys.watchlist, queryFn: watchlistApi.list });
}

/** Maps CIK → watchlist entry id for quick "is watched" checks. */
export function useWatchedCiks(): Map<string, number> | undefined {
  const { data } = useWatchlist();
  return data ? new Map(data.map((entry) => [entry.company.cik, entry.id])) : undefined;
}

export function useWatchlistMutations() {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: queryKeys.watchlist });

  const add = useMutation({
    mutationFn: (cik: string) => watchlistApi.add(cik),
    onSuccess: (entry) => {
      queryClient.setQueryData<WatchlistEntry[]>(queryKeys.watchlist, (current) =>
        current ? [...current.filter((e) => e.id !== entry.id), entry] : current,
      );
      return invalidate();
    },
  });

  const remove = useMutation({
    mutationFn: (id: number) => watchlistApi.remove(id),
    onSuccess: (_, id) => {
      queryClient.setQueryData<WatchlistEntry[]>(queryKeys.watchlist, (current) =>
        current?.filter((e) => e.id !== id),
      );
      return invalidate();
    },
  });

  const update = useMutation({
    mutationFn: (args: { id: number; formTypes: FormType[]; notificationsEnabled: boolean }) =>
      watchlistApi.update(args.id, args.formTypes, args.notificationsEnabled),
    onSuccess: (entry) => {
      queryClient.setQueryData<WatchlistEntry[]>(queryKeys.watchlist, (current) =>
        current?.map((e) => (e.id === entry.id ? entry : e)),
      );
    },
  });

  return { add, remove, update };
}
