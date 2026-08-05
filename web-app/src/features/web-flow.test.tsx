import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { BrowserRouter } from "react-router-dom";
import { api, tokenStore } from "../api/client";
import { AuthProvider } from "../auth/AuthContext";
import { ToastProvider } from "../ui/Toast";
import { App } from "../App";
import { DevicesPage, DeviceDetailsPage } from "./devices/DevicesPages";
import { DeviceStatusBadge } from "./devices/DeviceBits";

const auth = { user: { id: "u1", email: "owner@example.com", firstName: "Алия", status: "ACTIVE" }, centers: [{ center: { id: "c1", name: "Центр", slug: "center", status: "ACTIVE", timezone: "Asia/Almaty" }, role: "OWNER", status: "ACTIVE" }], activeCenter: { id: "c1", name: "Центр", slug: "center", status: "ACTIVE", timezone: "Asia/Almaty" }, accessToken: "access-token", accessTokenExpiresAt: "2030-01-01T00:00:00Z" };
const device = { id: "d1", name: "Детский планшет — Кабинет 1", role: "CHILD" as const, status: "ACTIVE" as const, isOnline: false, activatedAt: "2026-01-01T00:00:00Z" };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const empty = () => new Response(null, { status: 204 });

function TestProviders({ children }: { children: React.ReactNode }) { return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}><ToastProvider><BrowserRouter>{children}</BrowserRouter></ToastProvider></QueryClientProvider>; }

beforeEach(() => { tokenStore.clear(); vi.restoreAllMocks(); });

describe("web device flow", () => {
  it("redirects an unauthenticated user to login", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json({ error: { code: "UNAUTHORIZED", message: "Необходима авторизация" } }, 401)));
    render(<QueryClientProvider client={new QueryClient()}><ToastProvider><AuthProvider><BrowserRouter><App /></BrowserRouter></AuthProvider></ToastProvider></QueryClientProvider>);
    expect(await screen.findByRole("heading", { name: "Войдите в кабинет" })).toBeInTheDocument();
  });

  it("logs in and opens the cabinet", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/refresh")) return Promise.resolve(json({ error: { code: "UNAUTHORIZED", message: "x" } }, 401));
      if (url.includes("/login")) return Promise.resolve(json(auth));
      if (url.includes("/devices")) return Promise.resolve(json([]));
      return Promise.resolve(json(auth));
    }));
    render(<QueryClientProvider client={new QueryClient()}><ToastProvider><AuthProvider><BrowserRouter><App /></BrowserRouter></AuthProvider></ToastProvider></QueryClientProvider>);
    await screen.findByRole("heading", { name: "Войдите в кабинет" });
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "owner@example.com" } });
    fireEvent.change(screen.getByLabelText("Пароль"), { target: { value: "Password123" } });
    fireEvent.click(screen.getByRole("button", { name: "Войти" }));
    expect(await screen.findByRole("heading", { name: "Состояние устройств" })).toBeInTheDocument();
  });

  it("shows a safe API error on invalid login", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL) => {
      const url = String(input);
      if (url.includes("/refresh")) return Promise.resolve(json({ error: { code: "UNAUTHORIZED", message: "x" } }, 401));
      return Promise.resolve(json({ error: { code: "INVALID_CREDENTIALS", message: "raw backend message" } }, 401));
    }));
    render(<QueryClientProvider client={new QueryClient()}><ToastProvider><AuthProvider><BrowserRouter><App /></BrowserRouter></AuthProvider></ToastProvider></QueryClientProvider>);
    await screen.findByRole("heading", { name: "Войдите в кабинет" });
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "owner@example.com" } });
    fireEvent.change(screen.getByLabelText("Пароль"), { target: { value: "Password123" } });
    fireEvent.click(screen.getByRole("button", { name: "Войти" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Проверьте email и пароль.");
    expect(screen.queryByText("raw backend message")).not.toBeInTheDocument();
  });

  it("does not show another center's device when API isolates the list", async () => {
    tokenStore.set("access-token");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json([device])));
    render(<TestProviders><DevicesPage /></TestProviders>);
    expect(await screen.findByText(device.name)).toBeInTheDocument();
    expect(screen.queryByText("Чужой планшет")).not.toBeInTheDocument();
  });

  it("shows an activation code after creation", async () => {
    tokenStore.set("access-token");
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("activation-codes") && init?.method === "POST") return Promise.resolve(json({ id: "a1", activationCode: "ABCD2345", expiresAt: "2030-01-01T00:10:00Z", deviceName: "Кабинет 1", deviceRole: "CHILD" }));
      return Promise.resolve(json([]));
    });
    vi.stubGlobal("fetch", fetchMock);
    render(<TestProviders><DevicesPage /></TestProviders>);
    await screen.findByText("Планшеты ещё не подключены");
    fireEvent.click(screen.getAllByRole("button", { name: "Подключить планшет" })[0]);
    fireEvent.change(screen.getByLabelText("Название устройства"), { target: { value: "Кабинет 1" } });
    fireEvent.click(screen.getByRole("button", { name: "Получить код" }));
    expect(await screen.findByText("ABCD2345")).toBeInTheDocument();
  });

  it("marks an expired activation code as expired", async () => {
    tokenStore.set("access-token");
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => String(input).includes("activation-codes") && init?.method === "POST" ? Promise.resolve(json({ id: "a1", activationCode: "ABCD2345", expiresAt: "2020-01-01T00:00:00Z", deviceName: "Кабинет 1", deviceRole: "CHILD" })) : Promise.resolve(json([]))));
    render(<TestProviders><DevicesPage /></TestProviders>);
    await screen.findByText("Планшеты ещё не подключены"); fireEvent.click(screen.getAllByRole("button", { name: "Подключить планшет" })[0]); fireEvent.change(screen.getByLabelText("Название устройства"), { target: { value: "Кабинет 1" } }); fireEvent.click(screen.getByRole("button", { name: "Получить код" }));
    expect(await screen.findByText("Срок кода истёк")).toBeInTheDocument();
  });

  it("shows success when polling finds the newly activated device", async () => {
    tokenStore.set("access-token");
    let deviceRequests = 0;
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("activation-codes") && init?.method === "POST") return Promise.resolve(json({ id: "a1", activationCode: "ABCD2345", expiresAt: "2030-01-01T00:10:00Z", deviceName: "Кабинет 1", deviceRole: "CHILD" }));
      if (url.includes("/devices")) {
        deviceRequests += 1;
        return Promise.resolve(json(deviceRequests >= 3 ? [{ ...device, id: "new-device", name: "Кабинет 1", isOnline: true, activatedAt: new Date().toISOString() }] : []));
      }
      return Promise.resolve(json([]));
    }));
    render(<TestProviders><DevicesPage /></TestProviders>);
    await screen.findByText("Планшеты ещё не подключены"); fireEvent.click(screen.getAllByRole("button", { name: "Подключить планшет" })[0]); fireEvent.change(screen.getByLabelText("Название устройства"), { target: { value: "Кабинет 1" } }); fireEvent.click(screen.getByRole("button", { name: "Получить код" }));
    expect(await screen.findByRole("heading", { name: "Планшет подключён" })).toBeInTheDocument();
  });

  it("renders BLOCKED differently from offline", () => {
    const { rerender } = render(<DeviceStatusBadge device={{ status: "BLOCKED", isOnline: false }} />);
    expect(screen.getByText("Заблокирован")).toBeInTheDocument();
    rerender(<DeviceStatusBadge device={{ status: "ACTIVE", isOnline: false }} />);
    expect(screen.getByText("Офлайн")).toBeInTheDocument();
  });

  it("requires confirmation before unlink", async () => {
    tokenStore.set("access-token");
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(json(device)));
    const confirm = vi.spyOn(window, "confirm").mockReturnValue(false);
    render(<TestProviders><DeviceDetailsPage /></TestProviders>);
    // The route parameter is empty in this isolated rendering, but the API is still queried and enough for the UI test.
    await screen.findByRole("heading", { name: device.name });
    fireEvent.click(screen.getByRole("button", { name: "Отвязать планшет" }));
    expect(confirm).toHaveBeenCalled();
  });

  it("clears the in-memory session on logout", async () => {
    tokenStore.set("access-token"); vi.stubGlobal("fetch", vi.fn().mockResolvedValue(empty()));
    await api.logout();
    await waitFor(() => expect(tokenStore.get()).toBeNull());
  });
});
