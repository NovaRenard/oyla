import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, messageForError } from "../../api/client";
import type { CatalogStatus, Specialist } from "../../api/types";
import { useAuth } from "../../auth/AuthContext";
import { useToast } from "../../ui/Toast";
import { Failure, PageLoading } from "../dashboard/DashboardPage";
import { formatDate } from "../devices/DeviceBits";
import { CatalogBadge } from "../children/ChildrenPages";

type Filter = "ACTIVE" | "ARCHIVED" | "ALL";
const filterLabels: Record<Filter, string> = { ACTIVE: "Активные", ARCHIVED: "Архивные", ALL: "Все" };
const canManage = (role: string | null) => role === "OWNER" || role === "ADMIN";
const fullName = (specialist: Pick<Specialist, "firstName" | "lastName">) => [specialist.firstName, specialist.lastName].filter(Boolean).join(" ");

export function SpecialistsPage() {
  const { role } = useAuth(); const [filter, setFilter] = useState<Filter>("ACTIVE"); const [search, setSearch] = useState(""); const [addOpen, setAddOpen] = useState(false);
  const specialists = useQuery({ queryKey: ["specialists", filter, search], queryFn: () => api.listSpecialists({ status: filter, search }) });
  return <><div className="page-heading"><div><p className="eyebrow">Специалисты</p><h1>Специалисты центра</h1><p className="muted">Сотрудники, которые проводят занятия.</p></div>{canManage(role) && <button className="primary-button" onClick={() => setAddOpen(true)}>+ Добавить специалиста</button>}</div>
    <section className="panel"><div className="toolbar"><div className="filter-row">{(Object.keys(filterLabels) as Filter[]).map((value) => <button className={filter === value ? "filter active" : "filter"} key={value} onClick={() => setFilter(value)}>{filterLabels[value]}</button>)}</div><label className="search"><span className="sr-only">Поиск специалиста</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск по имени или специализации" /></label></div>
      {specialists.isLoading ? <PageLoading /> : specialists.isError ? <Failure message={messageForError(specialists.error)} onRetry={() => void specialists.refetch()} /> : specialists.data?.length ? <SpecialistsTable specialists={specialists.data} /> : <div className="empty-state"><strong>{search ? "Ничего не найдено" : filter === "ARCHIVED" ? "В архиве пока никого нет" : "Специалистов пока нет"}</strong><p>{canManage(role) ? "Добавьте специалиста, чтобы его можно было выбрать перед началом занятия." : "Обратитесь к администратору центра, чтобы добавить специалиста."}</p>{canManage(role) && !search && filter !== "ARCHIVED" && <button className="primary-button" onClick={() => setAddOpen(true)}>Добавить специалиста</button>}</div>}</section>
    {addOpen && <SpecialistDialog onClose={() => setAddOpen(false)} />}
  </>;
}

function SpecialistsTable({ specialists }: { specialists: Specialist[] }) { return <div className="table-wrap"><table><thead><tr><th>Имя</th><th>Фамилия</th><th>Специализация</th><th>Статус</th><th /></tr></thead><tbody>{specialists.map((specialist) => <tr key={specialist.id}><td><strong>{specialist.firstName}</strong></td><td>{specialist.lastName ?? "—"}</td><td>{specialist.specialization ?? "—"}</td><td><CatalogBadge status={specialist.status} /></td><td><Link className="table-link" to={`/specialists/${specialist.id}`}>Открыть</Link></td></tr>)}</tbody></table></div>; }

function SpecialistDialog({ onClose }: { onClose: () => void }) {
  const toast = useToast(); const queryClient = useQueryClient(); const [firstName, setFirstName] = useState(""); const [lastName, setLastName] = useState(""); const [specialization, setSpecialization] = useState("");
  const create = useMutation({ mutationFn: () => api.createSpecialist({ firstName, lastName, specialization }), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ["specialists"] }); toast.show("Специалист добавлен"); onClose(); }, onError: (error) => toast.show(messageForError(error), "error") });
  return <Dialog title="Добавить специалиста" onClose={onClose}><form className="form-stack" onSubmit={(event) => { event.preventDefault(); create.mutate(); }}><label className="field"><span>Имя</span><input autoFocus value={firstName} onChange={(event) => setFirstName(event.target.value)} required maxLength={100} /></label><label className="field"><span>Фамилия</span><input value={lastName} onChange={(event) => setLastName(event.target.value)} maxLength={100} /></label><label className="field"><span>Специализация</span><input value={specialization} onChange={(event) => setSpecialization(event.target.value)} maxLength={160} placeholder="Например, логопед" /></label><div className="dialog-actions"><button className="primary-button" disabled={!firstName.trim() || create.isPending}>{create.isPending ? "Добавляем…" : "Добавить"}</button><button type="button" className="text-button" onClick={onClose}>Отмена</button></div></form></Dialog>;
}

export function SpecialistDetailsPage() {
  const { specialistId = "" } = useParams(); const { role } = useAuth(); const toast = useToast(); const queryClient = useQueryClient(); const specialist = useQuery({ queryKey: ["specialist", specialistId], queryFn: () => api.getSpecialist(specialistId) });
  const [firstName, setFirstName] = useState(""); const [lastName, setLastName] = useState(""); const [specialization, setSpecialization] = useState("");
  useEffect(() => { if (specialist.data) { setFirstName(specialist.data.firstName); setLastName(specialist.data.lastName ?? ""); setSpecialization(specialist.data.specialization ?? ""); } }, [specialist.data]);
  const refreshLists = () => void queryClient.invalidateQueries({ queryKey: ["specialists"] });
  const update = useMutation({ mutationFn: () => api.updateSpecialist(specialistId, { firstName, lastName, specialization }), onSuccess: (result) => { queryClient.setQueryData(["specialist", specialistId], result); refreshLists(); toast.show("Данные специалиста сохранены"); }, onError: (error) => toast.show(messageForError(error), "error") });
  const archive = useMutation({ mutationFn: () => specialist.data?.status === "ACTIVE" ? api.archiveSpecialist(specialistId) : api.restoreSpecialist(specialistId), onSuccess: (result) => { queryClient.setQueryData(["specialist", specialistId], result); refreshLists(); toast.show(result.status === "ARCHIVED" ? "Специалист перемещён в архив" : "Специалист восстановлен"); }, onError: (error) => toast.show(messageForError(error), "error") });
  if (specialist.isLoading) return <PageLoading />;
  if (specialist.isError || !specialist.data) return <Failure message={specialist.isError ? messageForError(specialist.error) : "Специалист не найден."} onRetry={() => void specialist.refetch()} />;
  const item = specialist.data; const editable = canManage(role);
  return <><div className="page-heading"><div><Link className="back-link" to="/specialists">← Специалисты</Link><p className="eyebrow">Карточка специалиста</p><h1>{fullName(item)}</h1><p className="muted">{item.specialization ?? "Специализация не указана"}</p></div><CatalogBadge status={item.status} /></div><div className="detail-grid"><section className="panel"><h2>Основные данные</h2><dl className="details"><dt>Имя</dt><dd>{item.firstName}</dd><dt>Фамилия</dt><dd>{item.lastName ?? "—"}</dd><dt>Специализация</dt><dd>{item.specialization ?? "—"}</dd><dt>Добавлен</dt><dd>{formatDate(item.createdAt)}</dd><dt>Статус</dt><dd><CatalogBadge status={item.status} /></dd></dl><hr /><h2>Проведённые занятия</h2><div className="empty-inline">Занятий пока нет</div></section><section className="panel"><h2>Редактирование</h2>{editable ? <form className="form-stack" onSubmit={(event) => { event.preventDefault(); update.mutate(); }}><label className="field"><span>Имя</span><input value={firstName} onChange={(event) => setFirstName(event.target.value)} required maxLength={100} /></label><label className="field"><span>Фамилия</span><input value={lastName} onChange={(event) => setLastName(event.target.value)} maxLength={100} /></label><label className="field"><span>Специализация</span><input value={specialization} onChange={(event) => setSpecialization(event.target.value)} maxLength={160} /></label><button className="secondary-button" disabled={!firstName.trim() || update.isPending}>{update.isPending ? "Сохраняем…" : "Сохранить изменения"}</button></form> : <p className="muted">У вас есть доступ только к просмотру.</p>} {editable && <><hr /><div className="danger-zone"><h3>{item.status === "ACTIVE" ? "Архив" : "Восстановление"}</h3><p className="muted">{item.status === "ACTIVE" ? "Архивный специалист недоступен для новых занятий." : "Верните специалиста в активный список."}</p><button className={item.status === "ACTIVE" ? "danger-button" : "secondary-button"} disabled={archive.isPending} onClick={() => archive.mutate()}>{item.status === "ACTIVE" ? "Архивировать" : "Восстановить"}</button></div></>}</section></div></>;
}

function Dialog({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) { return <div className="modal-backdrop" role="presentation"><section className="modal" role="dialog" aria-modal="true" aria-label={title}><button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button><h2>{title}</h2>{children}</section></div>; }
