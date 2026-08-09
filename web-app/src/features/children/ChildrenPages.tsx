import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, messageForError } from "../../api/client";
import type { CatalogStatus, Child } from "../../api/types";
import { useAuth } from "../../auth/AuthContext";
import { useToast } from "../../ui/Toast";
import { Failure, PageLoading } from "../dashboard/DashboardPage";
import { formatDate } from "../devices/DeviceBits";
import { LessonsTable } from "../lessons/LessonsPages";

type Filter = "ACTIVE" | "ARCHIVED" | "ALL";
const filterLabels: Record<Filter, string> = { ACTIVE: "Активные", ARCHIVED: "Архивные", ALL: "Все" };

function canManage(role: string | null) { return role === "OWNER" || role === "ADMIN"; }
function fullName(child: Pick<Child, "firstName" | "lastName">) { return [child.firstName, child.lastName].filter(Boolean).join(" "); }
function ageOrBirthDate(birthDate?: string) {
  if (!birthDate) return "—";
  const birth = new Date(`${birthDate}T00:00:00`);
  const now = new Date();
  let age = now.getFullYear() - birth.getFullYear();
  const birthdayPassed = now.getMonth() > birth.getMonth() || (now.getMonth() === birth.getMonth() && now.getDate() >= birth.getDate());
  if (!birthdayPassed) age--;
  return `${age} ${age === 1 ? "год" : age >= 2 && age <= 4 ? "года" : "лет"} · ${new Intl.DateTimeFormat("ru-RU", { dateStyle: "medium" }).format(birth)}`;
}

export function ChildrenPage() {
  const { role } = useAuth(); const [filter, setFilter] = useState<Filter>("ACTIVE"); const [search, setSearch] = useState(""); const [addOpen, setAddOpen] = useState(false);
  const children = useQuery({ queryKey: ["children", filter, search], queryFn: () => api.listChildren({ status: filter, search }) });
  return <><div className="page-heading"><div><p className="eyebrow">Дети</p><h1>Дети центра</h1><p className="muted">Профили детей и история их занятий.</p></div>{canManage(role) && <button className="primary-button" onClick={() => setAddOpen(true)}>+ Добавить ребёнка</button>}</div>
    <section className="panel"><div className="toolbar"><div className="filter-row">{(Object.keys(filterLabels) as Filter[]).map((value) => <button className={filter === value ? "filter active" : "filter"} key={value} onClick={() => setFilter(value)}>{filterLabels[value]}</button>)}</div><label className="search"><span className="sr-only">Поиск ребёнка</span><input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Поиск по имени" /></label></div>
      {children.isLoading ? <PageLoading /> : children.isError ? <Failure message={messageForError(children.error)} onRetry={() => void children.refetch()} /> : children.data?.length ? <ChildrenTable children={children.data} /> : <div className="empty-state"><strong>{search ? "Ничего не найдено" : filter === "ARCHIVED" ? "В архиве пока никого нет" : "Детей пока нет"}</strong><p>{canManage(role) ? "Создайте профиль ребёнка, чтобы он появился в списке и был доступен специалисту на планшете." : "Обратитесь к администратору центра, чтобы добавить ребёнка."}</p>{canManage(role) && !search && filter !== "ARCHIVED" && <button className="primary-button" onClick={() => setAddOpen(true)}>Добавить ребёнка</button>}</div>}</section>
    {addOpen && <ChildDialog onClose={() => setAddOpen(false)} />}
  </>;
}

function ChildrenTable({ children }: { children: Child[] }) { return <div className="table-wrap"><table><thead><tr><th>Имя</th><th>Фамилия</th><th>Возраст / дата рождения</th><th>Статус</th><th>Добавлен</th><th /></tr></thead><tbody>{children.map((child) => <tr key={child.id}><td><strong>{child.firstName}</strong></td><td>{child.lastName ?? "—"}</td><td>{ageOrBirthDate(child.birthDate)}</td><td><CatalogBadge status={child.status} /></td><td>{formatDate(child.createdAt)}</td><td><Link className="table-link" to={`/children/${child.id}`}>Открыть</Link></td></tr>)}</tbody></table></div>; }

function ChildDialog({ onClose }: { onClose: () => void }) {
  const toast = useToast(); const queryClient = useQueryClient(); const [firstName, setFirstName] = useState(""); const [lastName, setLastName] = useState(""); const [birthDate, setBirthDate] = useState("");
  const create = useMutation({ mutationFn: () => api.createChild({ firstName, lastName, birthDate }), onSuccess: () => { void queryClient.invalidateQueries({ queryKey: ["children"] }); toast.show("Ребёнок добавлен"); onClose(); }, onError: (error) => toast.show(messageForError(error), "error") });
  return <Dialog title="Добавить ребёнка" onClose={onClose}><form className="form-stack" onSubmit={(event) => { event.preventDefault(); create.mutate(); }}><label className="field"><span>Имя</span><input autoFocus value={firstName} onChange={(event) => setFirstName(event.target.value)} required maxLength={100} /></label><label className="field"><span>Фамилия</span><input value={lastName} onChange={(event) => setLastName(event.target.value)} maxLength={100} /></label><label className="field"><span>Дата рождения</span><input type="date" value={birthDate} onChange={(event) => setBirthDate(event.target.value)} /></label><div className="dialog-actions"><button className="primary-button" disabled={!firstName.trim() || create.isPending}>{create.isPending ? "Добавляем…" : "Добавить"}</button><button type="button" className="text-button" onClick={onClose}>Отмена</button></div></form></Dialog>;
}

export function ChildDetailsPage() {
  const { childId = "" } = useParams(); const { role } = useAuth(); const toast = useToast(); const queryClient = useQueryClient(); const child = useQuery({ queryKey: ["child", childId], queryFn: () => api.getChild(childId) });
  const lessons = useQuery({ queryKey: ["child-lessons", childId], queryFn: () => api.childLessons(childId), enabled: Boolean(childId) });
  const [firstName, setFirstName] = useState(""); const [lastName, setLastName] = useState(""); const [birthDate, setBirthDate] = useState("");
  useEffect(() => { if (child.data) { setFirstName(child.data.firstName); setLastName(child.data.lastName ?? ""); setBirthDate(child.data.birthDate ?? ""); } }, [child.data]);
  const refreshLists = () => void queryClient.invalidateQueries({ queryKey: ["children"] });
  const update = useMutation({ mutationFn: () => api.updateChild(childId, { firstName, lastName, birthDate }), onSuccess: (result) => { queryClient.setQueryData(["child", childId], result); refreshLists(); toast.show("Данные ребёнка сохранены"); }, onError: (error) => toast.show(messageForError(error), "error") });
  const archive = useMutation({ mutationFn: () => child.data?.status === "ACTIVE" ? api.archiveChild(childId) : api.restoreChild(childId), onSuccess: (result) => { queryClient.setQueryData(["child", childId], result); refreshLists(); toast.show(result.status === "ARCHIVED" ? "Ребёнок перемещён в архив" : "Ребёнок восстановлен"); }, onError: (error) => toast.show(messageForError(error), "error") });
  if (child.isLoading) return <PageLoading />;
  if (child.isError || !child.data) return <Failure message={child.isError ? messageForError(child.error) : "Ребёнок не найден."} onRetry={() => void child.refetch()} />;
  const item = child.data; const editable = canManage(role);
  return <><div className="page-heading"><div><Link className="back-link" to="/children">← Дети</Link><p className="eyebrow">Карточка ребёнка</p><h1>{fullName(item)}</h1><p className="muted">{ageOrBirthDate(item.birthDate)}</p></div><CatalogBadge status={item.status} /></div><div className="detail-grid"><section className="panel"><h2>Основные данные</h2><dl className="details"><dt>Имя</dt><dd>{item.firstName}</dd><dt>Фамилия</dt><dd>{item.lastName ?? "—"}</dd><dt>Дата рождения</dt><dd>{item.birthDate ? new Intl.DateTimeFormat("ru-RU", { dateStyle: "long" }).format(new Date(`${item.birthDate}T00:00:00`)) : "—"}</dd><dt>Добавлен</dt><dd>{formatDate(item.createdAt)}</dd><dt>Статус</dt><dd><CatalogBadge status={item.status} /></dd></dl><hr /><h2>История занятий</h2>{lessons.isLoading ? <p className="muted">Загружаем…</p> : lessons.data?.length ? <LessonsTable lessons={lessons.data} /> : <div className="empty-inline">Занятий пока нет</div>}</section><section className="panel"><h2>Редактирование</h2>{editable ? <form className="form-stack" onSubmit={(event) => { event.preventDefault(); update.mutate(); }}><label className="field"><span>Имя</span><input value={firstName} onChange={(event) => setFirstName(event.target.value)} required maxLength={100} /></label><label className="field"><span>Фамилия</span><input value={lastName} onChange={(event) => setLastName(event.target.value)} maxLength={100} /></label><label className="field"><span>Дата рождения</span><input type="date" value={birthDate} onChange={(event) => setBirthDate(event.target.value)} /></label><button className="secondary-button" disabled={!firstName.trim() || update.isPending}>{update.isPending ? "Сохраняем…" : "Сохранить изменения"}</button></form> : <p className="muted">У вас есть доступ только к просмотру.</p>} {editable && <><hr /><div className="danger-zone"><h3>{item.status === "ACTIVE" ? "Архив" : "Восстановление"}</h3><p className="muted">{item.status === "ACTIVE" ? "Архивный ребёнок не появляется в новых занятиях." : "Верните ребёнка в активный список."}</p><button className={item.status === "ACTIVE" ? "danger-button" : "secondary-button"} disabled={archive.isPending} onClick={() => archive.mutate()}>{item.status === "ACTIVE" ? "Архивировать" : "Восстановить"}</button></div></>}</section></div></>;
}

export function CatalogBadge({ status }: { status: CatalogStatus }) { return <span className={`status-badge ${status === "ACTIVE" ? "online" : "muted-badge"}`}>{status === "ACTIVE" ? "Активен" : "В архиве"}</span>; }
function Dialog({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) { return <div className="modal-backdrop" role="presentation"><section className="modal" role="dialog" aria-modal="true" aria-label={title}><button className="modal-close" onClick={onClose} aria-label="Закрыть">×</button><h2>{title}</h2>{children}</section></div>; }
