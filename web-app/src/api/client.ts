import { ApiError, type ActivationCode, type AuthResponse, type Center, type Device, type DeviceRole, type DeviceStatus, type MeResponse } from "./types";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
let accessToken: string | null = null;
let refreshPromise: Promise<string | null> | null = null;

export const tokenStore = {
  set(token: string | null) { accessToken = token; },
  clear() { accessToken = null; },
  get() { return accessToken; },
};

type RequestOptions = Omit<RequestInit, "body"> & { body?: unknown; skipAuth?: boolean; retry?: boolean };

async function readError(response: Response): Promise<ApiError> {
  const fallback = { code: "UNKNOWN", message: "Не удалось выполнить запрос" };
  const payload = await response.json().catch(() => null) as { error?: typeof fallback } | null;
  return new ApiError(response.status, payload?.error ?? fallback);
}

async function raw<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body !== undefined) headers.set("Content-Type", "application/json");
  if (!options.skipAuth && accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
  const response = await fetch(`${apiBaseUrl}${path}`, {
    ...options,
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    credentials: "include",
  });
  if (response.status === 204) return undefined as T;
  if (!response.ok) throw await readError(response);
  return response.json() as Promise<T>;
}

async function refreshAccessToken(): Promise<string | null> {
  if (!refreshPromise) {
    refreshPromise = raw<AuthResponse>("/api/v1/auth/refresh", { method: "POST", body: {}, skipAuth: true })
      .then((auth) => {
        tokenStore.set(auth.accessToken);
        return auth.accessToken;
      })
      .catch(() => {
        tokenStore.clear();
        return null;
      })
      .finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

async function authenticated<T>(path: string, options: RequestOptions = {}): Promise<T> {
  try {
    return await raw<T>(path, options);
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && !options.retry) {
      const token = await refreshAccessToken();
      if (token) return raw<T>(path, { ...options, retry: true });
    }
    throw error;
  }
}

export const api = {
  async login(email: string, password: string) {
    const auth = await raw<AuthResponse>("/api/v1/auth/login", { method: "POST", body: { email, password }, skipAuth: true });
    tokenStore.set(auth.accessToken);
    return auth;
  },
  async register(input: { centerName: string; firstName: string; lastName?: string; email: string; password: string }) {
    const auth = await raw<AuthResponse>("/api/v1/auth/register-center", { method: "POST", body: input, skipAuth: true });
    tokenStore.set(auth.accessToken);
    return auth;
  },
  async restore() {
    const token = await refreshAccessToken();
    return token ? authenticated<MeResponse>("/api/v1/auth/me") : null;
  },
  async logout() {
    await raw<void>("/api/v1/auth/logout", { method: "POST", body: {}, skipAuth: true }).catch(() => undefined);
    tokenStore.clear();
  },
  me: () => authenticated<MeResponse>("/api/v1/auth/me"),
  currentCenter: () => authenticated<Center>("/api/v1/centers/current"),
  updateCenter: (input: { name?: string; timezone?: string }) => authenticated<Center>("/api/v1/centers/current", { method: "PATCH", body: input }),
  listDevices: (filters?: { role?: DeviceRole; status?: DeviceStatus; isOnline?: boolean }) => {
    const query = new URLSearchParams();
    if (filters?.role) query.set("role", filters.role);
    if (filters?.status) query.set("status", filters.status);
    if (filters?.isOnline !== undefined) query.set("isOnline", String(filters.isOnline));
    const suffix = query.size ? `?${query.toString()}` : "";
    return authenticated<Device[]>(`/api/v1/devices${suffix}`);
  },
  getDevice: (id: string) => authenticated<Device>(`/api/v1/devices/${id}`),
  createActivationCode: (input: { deviceName: string; deviceRole: DeviceRole }) => authenticated<ActivationCode>("/api/v1/devices/activation-codes", { method: "POST", body: input }),
  cancelActivationCode: (id: string) => authenticated<void>(`/api/v1/devices/activation-codes/${id}`, { method: "DELETE" }),
  updateDevice: (id: string, input: { name?: string; role?: DeviceRole; status?: DeviceStatus }) => authenticated<Device>(`/api/v1/devices/${id}`, { method: "PATCH", body: input }),
  unlinkDevice: (id: string) => authenticated<void>(`/api/v1/devices/${id}/unlink`, { method: "POST" }),
};

export function messageForError(error: unknown): string {
  if (!(error instanceof ApiError)) return "Нет соединения с сервером. Проверьте интернет и повторите попытку.";
  const known: Record<string, string> = {
    INVALID_CREDENTIALS: "Проверьте email и пароль.",
    UNAUTHORIZED: "Сессия закончилась. Войдите снова.",
    FORBIDDEN: "У вас нет прав для этого действия.",
    DEVICE_NOT_FOUND: "Устройство не найдено или недоступно.",
    INVALID_ACTIVATION_CODE: "Код подключения истёк или уже недействителен.",
    CONFLICT: "Это действие сейчас невозможно. Обновите страницу и повторите попытку.",
    RATE_LIMITED: "Слишком много попыток. Подождите немного и повторите.",
    VALIDATION_ERROR: "Проверьте заполнение полей.",
  };
  return known[error.body.code] ?? "Не удалось выполнить действие. Попробуйте ещё раз.";
}
