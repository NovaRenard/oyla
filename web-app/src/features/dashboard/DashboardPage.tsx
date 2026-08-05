import { useQuery } from "@tanstack/react-query";
import { Link } from "react-router-dom";
import { api, messageForError } from "../../api/client";
import { DeviceStatusBadge, formatDate, roleName } from "../devices/DeviceBits";

export function DashboardPage() {
  const devices = useQuery({ queryKey: ["devices"], queryFn: () => api.listDevices(), refetchInterval: 30_000 });
  if (devices.isLoading) return <PageLoading />;
  if (devices.isError) return <Failure message={messageForError(devices.error)} onRetry={() => void devices.refetch()} />;
  const all = devices.data ?? []; const active = all.filter((item) => item.status === "ACTIVE");
  const cards = [["Всего устройств", all.length], ["Устройств онлайн", active.filter((item) => item.isOnline).length], ["Детских планшетов", active.filter((item) => item.role === "CHILD").length], ["Планшетов специалиста", active.filter((item) => item.role === "SPECIALIST").length], ["Заблокированных", all.filter((item) => item.status === "BLOCKED").length]];
  return <><div className="page-heading"><div><p className="eyebrow">Обзор</p><h1>Состояние устройств</h1><p className="muted">Актуальные данные вашего центра.</p></div><Link to="/devices" className="secondary-button">Управлять устройствами</Link></div><section className="metric-grid">{cards.map(([label, value]) => <article className="metric-card" key={String(label)}><span>{label}</span><strong>{value}</strong></article>)}</section><section className="panel"><div className="panel-heading"><div><h2>Последняя активность устройств</h2><p>Последние подключившиеся планшеты.</p></div><Link to="/devices">Все устройства</Link></div>{all.length ? <div className="activity-list">{[...all].sort((a, b) => (b.lastSeenAt ?? "").localeCompare(a.lastSeenAt ?? "")).slice(0, 6).map((device) => <Link to={`/devices/${device.id}`} className="activity-row" key={device.id}><div><strong>{device.name}</strong><span>{roleName(device.role)} · версия {device.appVersion ?? "неизвестна"}</span></div><div><DeviceStatusBadge device={device} /><time>{formatDate(device.lastSeenAt)}</time></div></Link>)}</div> : <EmptyDevices />}</section></>;
}

export function EmptyDevices() { return <div className="empty-state"><strong>Планшеты ещё не подключены</strong><p>Создайте код подключения в разделе «Устройства», чтобы добавить первый планшет.</p><Link className="primary-button" to="/devices">Открыть устройства</Link></div>; }
export function PageLoading() { return <div className="panel centered">Загружаем данные…</div>; }
export function Failure({ message, onRetry }: { message: string; onRetry: () => void }) { return <div className="panel centered"><strong>Не удалось загрузить данные</strong><p>{message}</p><button className="secondary-button" onClick={onRetry}>Попробовать снова</button></div>; }
