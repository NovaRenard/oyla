export type DeviceRole = "CHILD" | "SPECIALIST";
export type DeviceStatus = "ACTIVE" | "BLOCKED" | "UNLINKED";
export type CatalogStatus = "ACTIVE" | "ARCHIVED";
export type ContentOwnership = "SYSTEM" | "CENTER";
export type ActivityType = "SINGLE_CHOICE";
export type MediaType = "IMAGE" | "AUDIO";

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

export type LessonStatus = "WAITING_FOR_CHILD" | "READY" | "COMPLETED" | "CANCELLED" | "EXPIRED";

/** Web history deliberately excludes session/device access tokens and legacy connection codes. */
export interface Lesson {
  id: string;
  childId: string;
  childName: string;
  specialistId: string;
  specialistName: string;
  status: LessonStatus;
  startedAt: string;
  completedAt?: string;
  durationMs?: number;
  exerciseCount: number;
  templateName?: string;
}

export interface MediaAsset { id: string; ownership: ContentOwnership; type: MediaType; originalFilename: string; mimeType: string; sizeBytes: number; url: string; createdAt: string; }
export interface ContentExerciseOption { id: string; label?: string; imageAssetId?: string; imageUrl?: string; localImageAssetKey?: string; sortOrder: number; isCorrect: boolean; }
export interface ContentExercise { id: string; ownership: ContentOwnership; activityType: ActivityType; title: string; instructionText: string; instructionAudioAssetId?: string; audioUrl?: string; localAudioAssetKey?: string; status: CatalogStatus; options: ContentExerciseOption[]; templateUsageCount: number; createdAt: string; updatedAt: string; }
export interface LessonTemplateItem { id: string; exerciseId: string; position: number; exerciseTitle?: string; activityType?: ActivityType; }
export interface LessonTemplate { id: string; ownership: ContentOwnership; name: string; description?: string; status: CatalogStatus; exerciseCount: number; items: LessonTemplateItem[]; createdAt: string; updatedAt: string; }

export interface LessonExercise {
  position: number;
  exerciseId: string;
  instructionText: string;
  status: string;
  attemptCount: number;
  incorrectAttempts: number;
  timeToCorrectMs?: number;
}

export interface LessonDetails {
  lesson: Lesson;
  specialistDeviceId: string;
  specialistDeviceName: string;
  childDeviceId: string;
  childDeviceName: string;
  exercises: LessonExercise[];
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
