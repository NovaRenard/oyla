import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, tokenStore } from "../api/client";
import { AuthProvider, useAuth } from "./AuthContext";
import { App } from "../App";

const auth = {
  user: { id: "u1", email: "owner@example.com", firstName: "Алия", status: "ACTIVE" },
  centers: [{ center: { id: "c1", name: "Центр", slug: "center", status: "ACTIVE", timezone: "Asia/Almaty" }, role: "OWNER", status: "ACTIVE" }],
  activeCenter: { id: "c1", name: "Центр", slug: "center", status: "ACTIVE", timezone: "Asia/Almaty" },
  accessToken: "new-access", accessTokenExpiresAt: "2030-01-01T00:00:00Z",
};
const me = { user: auth.user, centers: auth.centers, activeCenter: auth.activeCenter };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

function Probe() {
  const { user, signIn, signOut } = useAuth();
  return <><output>{user?.email ?? "anonymous"}</output><button onClick={() => void signIn("owner@example.com", "Password123")}>sign in</button><button onClick={() => void api.currentCenter().catch(() => undefined)}>protected request</button><button onClick={() => void signOut()}>sign out</button></>;
}
function renderAuth(queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return { queryClient, ...render(<QueryClientProvider client={queryClient}><AuthProvider><MemoryRouter><Probe /></MemoryRouter></AuthProvider></QueryClientProvider>) };
}

beforeEach(() => { tokenStore.clear(); vi.restoreAllMocks(); });

describe("web session lifecycle", () => {
  it("a failed refresh clears the AuthContext user", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/refresh")) return Promise.resolve(json({ error: { code: "UNAUTHORIZED" } }, 401));
      if (url.includes("/login")) return Promise.resolve(json(auth));
      return Promise.resolve(json({ error: { code: "UNAUTHORIZED" } }, 401));
    }));
    renderAuth();
    await screen.findByText("anonymous");
    fireEvent.click(screen.getByRole("button", { name: "sign in" }));
    expect(await screen.findByText("owner@example.com")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "protected request" }));
    await waitFor(() => expect(screen.getByText("anonymous")).toBeInTheDocument());
  });

  it("simultaneous 401 responses share one refresh request and retry once", async () => {
    tokenStore.set("expired");
    let refreshCalls = 0; let protectedCalls = 0;
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/refresh")) { refreshCalls++; return Promise.resolve(json(auth)); }
      protectedCalls++;
      return Promise.resolve(protectedCalls <= 2 ? json({ error: { code: "UNAUTHORIZED" } }, 401) : json(auth.activeCenter));
    }));
    await Promise.all([api.currentCenter(), api.currentCenter()]);
    expect(refreshCalls).toBe(1);
    expect(protectedCalls).toBe(4);
  });

  it("a repeated 401 after a successful refresh does not loop", async () => {
    tokenStore.set("expired");
    let refreshCalls = 0;
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      if (String(input).includes("/refresh")) { refreshCalls++; return Promise.resolve(json(auth)); }
      return Promise.resolve(json({ error: { code: "UNAUTHORIZED" } }, 401));
    }));
    await expect(api.currentCenter()).rejects.toMatchObject({ status: 401 });
    expect(refreshCalls).toBe(1);
  });

  it("failed refresh sends a protected route to login", async () => {
    tokenStore.set("expired");
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => String(input).includes("/refresh")
      ? Promise.resolve(json({ error: { code: "UNAUTHORIZED" } }, 401))
      : Promise.resolve(json({ error: { code: "UNAUTHORIZED" } }, 401))));
    render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><AuthProvider><MemoryRouter initialEntries={["/overview"]}><App /></MemoryRouter></AuthProvider></QueryClientProvider>);
    expect(await screen.findByRole("heading", { name: "Войдите в кабинет" })).toBeInTheDocument();
  });

  it("logout clears profile and query cache", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/refresh")) return Promise.resolve(json({ error: { code: "UNAUTHORIZED" } }, 401));
      if (url.includes("/login")) return Promise.resolve(json(auth));
      return Promise.resolve(new Response(null, { status: 204 }));
    }));
    const { queryClient } = renderAuth();
    await screen.findByText("anonymous");
    fireEvent.click(screen.getByRole("button", { name: "sign in" }));
    await screen.findByText("owner@example.com");
    queryClient.setQueryData(["devices"], ["stale-device"]);
    fireEvent.click(screen.getByRole("button", { name: "sign out" }));
    await waitFor(() => expect(screen.getByText("anonymous")).toBeInTheDocument());
    expect(queryClient.getQueryData(["devices"])).toBeUndefined();
  });
});
