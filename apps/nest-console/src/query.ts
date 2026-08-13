import { QueryClient } from "@tanstack/react-query";

export const EVIDENCE_KEY = "local-v1-evidence";

export function queryClientFactory() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
    },
  });
}
