export type DeviceRole = "CHILD" | "SPECIALIST";
export type DeviceStatus = "ACTIVE" | "BLOCKED" | "UNLINKED";
export type CatalogStatus = "ACTIVE" | "ARCHIVED";

export interface User {
  id: string;
  email: string;
  firstName: string;
  lastName?: string;
  status: "ACTIVE" | "BLOCKED";
}

export interface Center {
  id: string;
  name: string;
  slug: string;
  status: string;
  timezone: string;
}

export interface Membership {
  center: Center;
  role: "OWNER" | "ADMIN" | "METHODIST" | "SPECIALIST";
  status: string;
}

export interface AuthResponse {
  user: User;
  centers: Membership[];
  activeCenter?: Center;
  accessToken: string;
  refreshToken?: string;
  accessTokenExpiresAt: string;
}

export interface MeResponse {
  user: User;
  centers: Membership[];
  activeCenter?: Center;
}

export interface Device {
  id: string;
  name: string;
  role: DeviceRole;
  status: DeviceStatus;
  appVersion?: string;
  androidVersion?: string;
  model?: string;
  lastSeenAt?: string;
  activatedAt?: string;
  isOnline: boolean;
}

export interface ActivationCode {
  id: string;
  activationCode: string;
  expiresAt: string;
  deviceName: string;
  deviceRole: DeviceRole;
}

export interface Child {
  id: string;
  firstName: string;
  lastName?: string;
  birthDate?: string;
  status: CatalogStatus;
  createdAt: string;
  updatedAt: string;
}

export interface Specialist {
  id: string;
  firstName: string;
  lastName?: string;
  specialization?: string;
  status: CatalogStatus;
  createdAt: string;
  updatedAt: string;
}

export interface ApiErrorBody {
  code: string;
  message: string;
  details?: string;
  requestId?: string;
}

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly body: ApiErrorBody) {
    super(body.message);
  }
}
