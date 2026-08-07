import { BrowserRouter, Route, Routes } from "react-router";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const queryClient = new QueryClient();

function ScaffoldPage() {
  return <h1>nest console scaffold</h1>;
}

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<ScaffoldPage />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
