import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useMemo, useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useNavigate, useParams } from "react-router-dom";
import { z } from "zod";
import { api, messageForError } from "../../api/client";
import type { ActivationCode, Device, DeviceRole } from "../../api/types";
import { useToast } from "../../ui/Toast";
import { Failure, PageLoading } from "../dashboard/DashboardPage";
import { DeviceStatusBadge, formatDate, roleName } from "./DeviceBits";

type Filter = "ALL" | "CHILD" | "SPECIALIST" | "ONLINE" | "OFFLINE" | "BLOCKED";
const filterLabels: Record<Filter, string> = { ALL: "Все", CHILD: "Детские", SPECIALIST: "Специалиста", ONLINE: "Онлайн", OFFLINE: "Офлайн", BLOCKED: "Заблокированные" };

export function DevicesPage() {
  const [filter, setFilter] = useState<Filter>("ALL"); const [search, setSearch] = useState(""); const [connectOpen, setConnectOpen] = useState(false);
  const devices = useQuery({ queryKey: ["devices"], queryFn: () => api.listDevices(), refetchInterval: 30_000 });
  const filtered = useMemo(() => (devices.data ?? []).filter((device) => {
    const matchesSearch = device.name.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase());
    const matchesFilter = filter === "ALL" || filter === device.role || (filter === "ONLINE" && device.status === "ACTIVE" && device.isOnline) || (filter === "OFFLINE" && device.status === "ACTIVE" && !device.isOnline) || (filter === "BLOCKED" && device.status === "BLOCKED");
    return matchesSearch && matchesFilter;
  }), [devices.data, filter, search]);
  return <><div className="page-heading"><div><p className="eyebrow">Устройства</p><h1>Планшеты центра</h1><p className="muted">Подключайте, проверяйте состояние и управляйте доступом.</p></div><button className="primary-button" onClick={() => setConnectOpen(true)}>Подключить планшет</button></div>
    <section className="panel"><div className="toolbar"><div className="filter-row">{(Object.keys(filterLabels) as Filter[]).map((item) => <button key={item} className={filter === item ? "filter active" : "filter"} onClick={() => setFilter(item)}>{filterLabels[item]}</button>)}</div><label className="search"><span className="sr-only">Поиск устройства</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск по названию" /></label></div>
      {devices.isLoading ? <PageLoading /> : devices.isError ? <Failure message={messageForError(devices.error)} onRetry={() => void devices.refetch()} /> : filtered.length ? <DeviceTable devices={filtered} /> : <div className="empty-state"><strong>{devices.data?.length ? "Ничего не найдено" : "Планшеты ещё не подключены"}</strong><p>{devices.data?.length ? "Измените фильтр или поисковый запрос." : "Создайте код подключения и введите его в приложении Oyla на планшете."}</p>{!devices.data?.length && <button className="primary-button" onClick={() => setConnectOpen(true)}>Подключить планшет</button>}</div>}</section>
    {connectOpen && <ConnectDeviceDialog onClose={() => setConnectOpen(false)} onConnected={() => { void devices.refetch(); setConnectOpen(false); }} />}
  </>;
}

function DeviceTable({ devices }: { devices: Device[] }) {
  return <div className="table-wrap"><table><thead><tr><th>Название</th><th>Роль</th><th>Статус</th><th>Модель</th><th>Android</th><th>Версия приложения</th><th>Последнее подключение</th><th>Активация</th><th /></tr></thead><tbody>{devices.map((device) => <tr key={device.id}><td><strong>{device.name}</strong></td><td>{roleName(device.role)}</td><td><DeviceStatusBadge device={device} /></td><td>{device.model ?? "—"}</td><td>{device.androidVersion ?? "—"}</td><td>{device.appVersion ?? "—"}</td><td>{formatDate(device.lastSeenAt)}</td><td>{formatDate(device.activatedAt)}</td><td><Link className="table-link" to={`/devices/${device.id}`}>Открыть</Link></td></tr>)}</tbody></table></div>;
}

const activationSchema = z.object({ deviceName: z.string().trim().min(2, "Укажите название устройства").max(160), deviceRole: z.enum(["CHILD", "SPECIALIST"]) });
type ActivationForm = z.infer<typeof activationSchema>;

function ConnectDeviceDialog({ onClose, onConnected }: { onClose: () => void; onConnected: () => void }) {
  const toast = useToast(); const [activation, setActivation] = useState<ActivationCode | null>(null); const [baselineDeviceIds, setBaselineDeviceIds] = useState<Set<string>>(new Set()); const [startedAt, setStartedAt] = useState(0); const [now, setNow] = useState(Date.now()); const [connected, setConnected] = useState<Device | null>(null);
  const devices = useQuery({ queryKey: ["activation-device-poll", activation?.id], queryFn: () => api.listDevices(), enabled: Boolean(activation && !connected && Date.parse(activation.expiresAt) > now), refetchInterval: activation && !connected && Date.parse(activation.expiresAt) > now ? 3_000 : false });
  const form = useForm<ActivationForm>({ resolver: zodResolver(activationSchema), defaultValues: { deviceName: "", deviceRole: "CHILD" } });
  const create = useMutation({ mutationFn: api.createActivationCode, onSuccess: async (code) => { const current = await api.listDevices(); setBaselineDeviceIds(new Set(current.map((device) => device.id))); setStartedAt(Date.now()); setActivation(code); setNow(Date.now()); }, onError: (error) => toast.show(messageForError(error), "error") });
  useEffect(() => { if (!activation || connected) return; const id = window.setInterval(() => setNow(Date.now()), 1_000); return () => window.clearInterval(id); }, [activation, connected]);
  useEffect(() => {
    if (!activation || !devices.data || connected) return;
    const newDevice = devices.data.find((device) => !baselineDeviceIds.has(device.id) && device.name === activation.deviceName && device.role === activation.deviceRole && device.status === "ACTIVE" && Date.parse(device.activatedAt ?? "") >= startedAt - 5_000);
    if (newDevice) { setConnected(newDevice); void devices.refetch(); }
  }, [activation, baselineDeviceIds, connected, devices, startedAt]);
  const expired = activation ? Date.parse(activation.expiresAt) <= now : false;
  const remaining = activation ? Math.max(0, Math.ceil((Date.parse(activation.expiresAt) - now) / 1_000)) : 0;
  const close = () => { if (activation && !connected && !expired) void api.cancelActivationCode(activation.id); onClose(); };
  if (connected) return <Dialog title="Планшет подключён" onClose={onClose}><div className="success-state"><div className="success-mark">✓</div><strong>{connected.name}</strong><p>Устройство появилось в списке и получило доступ центра.</p><Link className="primary-button" to={`/devices/${connected.id}`} onClick={onConnected}>Открыть карточку устройства</Link><button className="text-button" onClick={onConnected}>Закрыть</button></div></Dialog>;
  if (activation) return <Dialog title="Введите код на планшете" onClose={close}><div className="activation-code-state"><p className="code-display">{activation.activationCode}</p><p className={expired ? "timer expired" : "timer"}>{expired ? "Срок кода истёк" : `Действует ещё ${formatCountdown(remaining)}`}</p><ol><li>Откройте Oyla на планшете.</li><li>Введите этот код.</li><li>Дождитесь подтверждения подключения.</li></ol>{devices.isError && <p className="inline-error">Не удаётся проверить подключение. Код остаётся действительным, попробуйте позже.</p>}<div className="dialog-actions"><button className="secondary-button" onClick={() => { if (!navigator.clipboard) { toast.show("Копирование недоступно в этом браузере", "error"); return; } void navigator.clipboard.writeText(activation.activationCode).then(() => toast.show("Код скопирован")).catch(() => toast.show("Не удалось скопировать код", "error")); }}>Скопировать код</button>{expired ? <button className="primary-button" onClick={() => { setActivation(null); setConnected(null); form.reset(); }}>Создать новый код</button> : <button className="text-button" onClick={close}>Отменить</button>}</div></div></Dialog>;
  return <Dialog title="Подключить планшет" onClose={onClose}><p className="muted">Сначала задайте понятное название и роль планшета.</p><form className="form-stack" onSubmit={form.handleSubmit((value) => create.mutate(value))} noValidate><label className="field"><span>Название устройства</span><input autoFocus placeholder="Детский планшет — Кабинет 1" {...form.register("deviceName")} />{form.formState.errors.deviceName?.message && <small className="field-error">{form.formState.errors.deviceName.message}</small>}</label><label className="field"><span>Роль</span><select {...form.register("deviceRole")}><option value="CHILD">Детский планшет</option><option value="SPECIALIST">Планшет специалиста</option></select></label><div className="dialog-actions"><button className="primary-button" disabled={create.isPending}>{create.isPending ? "Создаём…" : "Получить код"}</button><button type="button" className="text-button" onClick={onClose}>Отмена</button></div></form></Dialog>;
}

function Dialog({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) { return <div className="modal-backdrop" role="presentation"><section className="modal" role="dialog" aria-modal="true" aria-label={title}><button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button><h2>{title}</h2>{children}</section></div>; }
function formatCountdown(seconds: number) { const minutes = Math.floor(seconds / 60); return `${minutes}:${String(seconds % 60).padStart(2, "0")}`; }

const deviceEditSchema = z.object({ name: z.string().trim().min(2, "Укажите название устройства").max(160), role: z.enum(["CHILD", "SPECIALIST"]) });
type DeviceEdit = z.infer<typeof deviceEditSchema>;

export function DeviceDetailsPage() {
  const { deviceId = "" } = useParams(); const navigate = useNavigate(); const toast = useToast(); const queryClient = useQueryClient(); const deviceQuery = useQuery({ queryKey: ["device", deviceId], queryFn: () => api.getDevice(deviceId) });
  const edit = useForm<DeviceEdit>({ resolver: zodResolver(deviceEditSchema) });
  useEffect(() => { if (deviceQuery.data) edit.reset({ name: deviceQuery.data.name, role: deviceQuery.data.role }); }, [deviceQuery.data, edit]);
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: ["devices"] });
  const mutation = useMutation({ mutationFn: (input: { name?: string; role?: DeviceRole; status?: "ACTIVE" | "BLOCKED" }) => api.updateDevice(deviceId, input), onSuccess: (device) => { queryClient.setQueryData(["device", deviceId], device); invalidate(); toast.show("Изменения сохранены"); }, onError: (error) => toast.show(messageForError(error), "error") });
  const unlink = useMutation({ mutationFn: () => api.unlinkDevice(deviceId), onSuccess: () => { invalidate(); toast.show("Планшет отвязан"); navigate("/devices", { replace: true }); }, onError: (error) => toast.show(messageForError(error), "error") });
  if (deviceQuery.isLoading) return <PageLoading />;
  if (deviceQuery.isError) return <Failure message={messageForError(deviceQuery.error)} onRetry={() => void deviceQuery.refetch()} />;
  if (!deviceQuery.data) return <Failure message="Устройство не найдено." onRetry={() => void deviceQuery.refetch()} />;
  const device = deviceQuery.data;
  return <><div className="page-heading"><div><Link className="back-link" to="/devices">← Устройства</Link><p className="eyebrow">Карточка устройства</p><h1>{device.name}</h1><p className="muted">{roleName(device.role)}</p></div><DeviceStatusBadge device={device} /></div><div className="detail-grid"><section className="panel"><h2>Данные планшета</h2><dl className="details"><dt>Центр</dt><dd>Текущий центр</dd><dt>Статус</dt><dd><DeviceStatusBadge device={device} /></dd><dt>Device ID</dt><dd className="mono">{device.id}</dd><dt>Модель</dt><dd>{device.model ?? "—"}</dd><dt>Android</dt><dd>{device.androidVersion ?? "—"}</dd><dt>Версия приложения</dt><dd>{device.appVersion ?? "—"}</dd><dt>Активирован</dt><dd>{formatDate(device.activatedAt)}</dd><dt>Последнее подключение</dt><dd>{formatDate(device.lastSeenAt)}</dd></dl></section><section className="panel"><h2>Настройки</h2><form className="form-stack" onSubmit={edit.handleSubmit((data) => mutation.mutate(data))} noValidate><label className="field"><span>Название</span><input {...edit.register("name")} />{edit.formState.errors.name?.message && <small className="field-error">{edit.formState.errors.name.message}</small>}</label><label className="field"><span>Роль</span><select {...edit.register("role")}><option value="CHILD">Детский планшет</option><option value="SPECIALIST">Планшет специалиста</option></select></label><button className="secondary-button" disabled={mutation.isPending}>{mutation.isPending ? "Сохраняем…" : "Сохранить изменения"}</button></form><hr /><div className="danger-zone"><h3>Доступ устройства</h3>{device.status === "BLOCKED" ? <button className="secondary-button" disabled={mutation.isPending} onClick={() => mutation.mutate({ status: "ACTIVE" })}>Разблокировать</button> : <button className="danger-button" disabled={mutation.isPending || device.status === "UNLINKED"} onClick={() => window.confirm("Заблокировать планшет? Он не сможет открыть занятия до разблокировки.") && mutation.mutate({ status: "BLOCKED" })}>Заблокировать</button>}<button className="danger-link" disabled={unlink.isPending || device.status === "UNLINKED"} onClick={() => window.confirm("После отвязки планшет потеряет доступ к центру. Для повторного подключения потребуется новый код активации. Продолжить?") && unlink.mutate()}>Отвязать планшет</button></div></section></div></>;
}
