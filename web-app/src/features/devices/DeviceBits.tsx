import type { Device, DeviceRole } from "../../api/types";

export const roleName = (role: DeviceRole) => role === "CHILD" ? "Детский планшет" : "Планшет специалиста";
export const formatDate = (value?: string) => value ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value)) : "—";

export function DeviceStatusBadge({ device }: { device: Pick<Device, "status" | "isOnline"> }) {
  if (device.status === "BLOCKED") return <span className="status-badge blocked">Заблокирован</span>;
  if (device.status === "UNLINKED") return <span className="status-badge muted-badge">Отвязан</span>;
  return <span className={`status-badge ${device.isOnline ? "online" : "offline"}`}><i />{device.isOnline ? "Онлайн" : "Офлайн"}</span>;
}
