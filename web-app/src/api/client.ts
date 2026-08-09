import { ApiError, type ActivationCode, type AuthResponse, type Center, type Child, type ContentExercise, type ContentOwnership, type Device, type DeviceRole, type DeviceStatus, type Lesson, type LessonDetails, type LessonStatus, type LessonTemplate, type MediaAsset, type MeResponse, type Specialist } from "./types";

const apiBaseUrl = import.meta.env.VITE_API_BASE_URL ?? "";
let accessToken: string | null = null;
let refreshPromise: Promise<string | null> | null = null;
let sessionEnded = false;
const sessionEndListeners = new Set<() => void>();

/** AuthContext owns navigation and cache eviction; the API layer only publishes session state. */
export function subscribeToSessionEnd(listener: () => void) {
  sessionEndListeners.add(listener);
  return () => {
    sessionEndListeners.delete(listener);
  };
}

function endSession() {
  tokenStore.clear();
  if (sessionEnded) return;
  sessionEnded = true;
  sessionEndListeners.forEach((listener) => listener());
}

export const tokenStore = {
  set(token: string | null) { accessToken = token; if (token) sessionEnded = false; },
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
        endSession();
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
      if (token) {
        try {
          return await raw<T>(path, { ...options, retry: true });
        } catch (retryError) {
          if (retryError instanceof ApiError && retryError.status === 401) endSession();
          throw retryError;
        }
      }
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
  async restore() {
    const token = await refreshAccessToken();
    return token ? authenticated<MeResponse>("/api/v1/auth/me") : null;
  },
  async logout() {
    await raw<void>("/api/v1/auth/logout", { method: "POST", body: {}, skipAuth: true }).catch(() => undefined);
    endSession();
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
  listChildren: (filters?: { status?: "ACTIVE" | "ARCHIVED" | "ALL"; search?: string }) => {
    const query = new URLSearchParams();
    if (filters?.status) query.set("status", filters.status);
    if (filters?.search?.trim()) query.set("search", filters.search.trim());
    return authenticated<Child[]>(`/api/v1/children${query.size ? `?${query}` : ""}`);
  },
  getChild: (id: string) => authenticated<Child>(`/api/v1/children/${id}`),
  createChild: (input: { firstName: string; lastName?: string; birthDate?: string }) => authenticated<Child>("/api/v1/children", { method: "POST", body: input }),
  updateChild: (id: string, input: { firstName?: string; lastName?: string; birthDate?: string }) => authenticated<Child>(`/api/v1/children/${id}`, { method: "PATCH", body: input }),
  archiveChild: (id: string) => authenticated<Child>(`/api/v1/children/${id}/archive`, { method: "POST" }),
  restoreChild: (id: string) => authenticated<Child>(`/api/v1/children/${id}/restore`, { method: "POST" }),
  listSpecialists: (filters?: { status?: "ACTIVE" | "ARCHIVED" | "ALL"; search?: string }) => {
    const query = new URLSearchParams();
    if (filters?.status) query.set("status", filters.status);
    if (filters?.search?.trim()) query.set("search", filters.search.trim());
    return authenticated<Specialist[]>(`/api/v1/specialists${query.size ? `?${query}` : ""}`);
  },
  getSpecialist: (id: string) => authenticated<Specialist>(`/api/v1/specialists/${id}`),
  createSpecialist: (input: { firstName: string; lastName?: string; specialization?: string }) => authenticated<Specialist>("/api/v1/specialists", { method: "POST", body: input }),
  updateSpecialist: (id: string, input: { firstName?: string; lastName?: string; specialization?: string }) => authenticated<Specialist>(`/api/v1/specialists/${id}`, { method: "PATCH", body: input }),
  archiveSpecialist: (id: string) => authenticated<Specialist>(`/api/v1/specialists/${id}/archive`, { method: "POST" }),
  restoreSpecialist: (id: string) => authenticated<Specialist>(`/api/v1/specialists/${id}/restore`, { method: "POST" }),
  listLessons: (filters?: { childId?: string; specialistId?: string; status?: LessonStatus }) => {
    const query = new URLSearchParams();
    if (filters?.childId) query.set("childId", filters.childId);
    if (filters?.specialistId) query.set("specialistId", filters.specialistId);
    if (filters?.status) query.set("status", filters.status);
    return authenticated<Lesson[]>(`/api/v1/lessons${query.size ? `?${query}` : ""}`);
  },
  getLesson: (id: string) => authenticated<LessonDetails>(`/api/v1/lessons/${id}`),
  childLessons: (id: string, status?: LessonStatus) => authenticated<Lesson[]>(`/api/v1/children/${id}/lessons${status ? `?status=${status}` : ""}`),
  specialistLessons: (id: string, status?: LessonStatus) => authenticated<Lesson[]>(`/api/v1/specialists/${id}/lessons${status ? `?status=${status}` : ""}`),
  listExercises: (filters?: { ownership?: ContentOwnership; status?: "ACTIVE" | "ARCHIVED"; search?: string }) => {
    const query = new URLSearchParams(); if (filters?.ownership) query.set("ownership", filters.ownership); if (filters?.status) query.set("status", filters.status); if (filters?.search?.trim()) query.set("search", filters.search.trim());
    return authenticated<ContentExercise[]>(`/api/v1/exercises${query.size ? `?${query}` : ""}`);
  },
  getExercise: (id: string) => authenticated<ContentExercise>(`/api/v1/exercises/${id}`),
  createExercise: (input: { title: string; instructionText: string; instructionAudioAssetId?: string; options: Array<{ id?: string; label?: string; imageAssetId?: string; isCorrect: boolean }> }) => authenticated<ContentExercise>("/api/v1/exercises", { method: "POST", body: input }),
  updateExercise: (id: string, input: { title?: string; instructionText?: string; instructionAudioAssetId?: string; options?: Array<{ id?: string; label?: string; imageAssetId?: string; isCorrect: boolean }> }) => authenticated<ContentExercise>(`/api/v1/exercises/${id}`, { method: "PATCH", body: input }),
  archiveExercise: (id: string) => authenticated<ContentExercise>(`/api/v1/exercises/${id}/archive`, { method: "POST" }),
  restoreExercise: (id: string) => authenticated<ContentExercise>(`/api/v1/exercises/${id}/restore`, { method: "POST" }),
  duplicateExercise: (id: string) => authenticated<ContentExercise>(`/api/v1/exercises/${id}/duplicate`, { method: "POST" }),
  listTemplates: (filters?: { ownership?: ContentOwnership; status?: "ACTIVE" | "ARCHIVED"; search?: string }) => {
    const query = new URLSearchParams(); if (filters?.ownership) query.set("ownership", filters.ownership); if (filters?.status) query.set("status", filters.status); if (filters?.search?.trim()) query.set("search", filters.search.trim());
    return authenticated<LessonTemplate[]>(`/api/v1/lesson-templates${query.size ? `?${query}` : ""}`);
  },
  getTemplate: (id: string) => authenticated<LessonTemplate>(`/api/v1/lesson-templates/${id}`),
  createTemplate: (input: { name: string; description?: string; items: Array<{ exerciseId: string }> }) => authenticated<LessonTemplate>("/api/v1/lesson-templates", { method: "POST", body: input }),
  updateTemplate: (id: string, input: { name?: string; description?: string; items?: Array<{ exerciseId: string }> }) => authenticated<LessonTemplate>(`/api/v1/lesson-templates/${id}`, { method: "PATCH", body: input }),
  archiveTemplate: (id: string) => authenticated<LessonTemplate>(`/api/v1/lesson-templates/${id}/archive`, { method: "POST" }),
  restoreTemplate: (id: string) => authenticated<LessonTemplate>(`/api/v1/lesson-templates/${id}/restore`, { method: "POST" }),
  duplicateTemplate: (id: string) => authenticated<LessonTemplate>(`/api/v1/lesson-templates/${id}/duplicate`, { method: "POST" }),
  async uploadMedia(type: "images" | "audio", file: File): Promise<MediaAsset> {
    const request = async () => {
      const headers = new Headers(); if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
      const body = new FormData(); body.append("file", file);
      const response = await fetch(`${apiBaseUrl}/api/v1/media/${type}`, { method: "POST", headers, body, credentials: "include" });
      if (!response.ok) throw await readError(response); return response.json() as Promise<MediaAsset>;
    };
    try { return await request(); } catch (error) { if (error instanceof ApiError && error.status === 401 && await refreshAccessToken()) return request(); throw error; }
  },
  async mediaBlob(url: string): Promise<Blob> {
    const request = async () => {
      const headers = new Headers(); if (accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
      const response = await fetch(`${apiBaseUrl}${url}`, { headers, credentials: "include" });
      if (!response.ok) throw await readError(response); return response.blob();
    };
    try { return await request(); } catch (error) { if (error instanceof ApiError && error.status === 401 && await refreshAccessToken()) return request(); throw error; }
  },
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
    REGISTRATION_DISABLED: "Регистрация центра сейчас недоступна.",
    VALIDATION_ERROR: "Проверьте заполнение полей.",
  };
  return known[error.body.code] ?? "Не удалось выполнить действие. Попробуйте ещё раз.";
}
