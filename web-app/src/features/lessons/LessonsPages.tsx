import { useQuery } from "@tanstack/react-query";
import { Link, useParams } from "react-router-dom";
import { useMemo, useState } from "react";
import { api, messageForError } from "../../api/client";
import type { Lesson, LessonStatus } from "../../api/types";
import { Failure, PageLoading } from "../dashboard/DashboardPage";
import { formatDate } from "../devices/DeviceBits";

const statuses: Array<"ALL" | LessonStatus> = ["ALL", "READY", "COMPLETED", "CANCELLED", "EXPIRED"];
const statusLabels: Record<"ALL" | LessonStatus, string> = { ALL: "Все статусы", WAITING_FOR_CHILD: "Ожидание", READY: "Идёт", COMPLETED: "Завершено", CANCELLED: "Отменено", EXPIRED: "Истекло" };

export function lessonStatus(status: LessonStatus) {
  const tone = status === "COMPLETED" ? "online" : status === "READY" ? "warning" : "muted-badge";
  return <span className={`status-badge ${tone}`}>{statusLabels[status]}</span>;
}

export function formatDuration(duration?: number) {
  if (duration === undefined) return "—";
  const seconds = Math.round(duration / 1000); const minutes = Math.floor(seconds / 60); const rest = seconds % 60;
  return minutes ? `${minutes} мин ${rest.toString().padStart(2, "0")} сек` : `${rest} сек`;
}

export function LessonsPage() {
  const lessons = useQuery({ queryKey: ["lessons"], queryFn: () => api.listLessons() });
  const [status, setStatus] = useState<"ALL" | LessonStatus>("ALL"); const [date, setDate] = useState(""); const [query, setQuery] = useState("");
  const visible = useMemo(() => (lessons.data ?? []).filter((lesson) => {
    const matchesStatus = status === "ALL" || lesson.status === status;
    const matchesDate = !date || lesson.startedAt.slice(0, 10) === date;
    const haystack = `${lesson.childName} ${lesson.specialistName} ${lesson.templateName ?? ""}`.toLocaleLowerCase();
    return matchesStatus && matchesDate && (!query.trim() || haystack.includes(query.trim().toLocaleLowerCase()));
  }), [lessons.data, status, date, query]);
  return <><div className="page-heading"><div><p className="eyebrow">История</p><h1>Занятия</h1><p className="muted">История запусков и завершённых занятий центра.</p></div></div>
    <section className="panel"><div className="toolbar"><div className="filter-row"><label className="field compact-field"><span>Статус</span><select value={status} onChange={(event) => setStatus(event.target.value as "ALL" | LessonStatus)}>{statuses.map((value) => <option key={value} value={value}>{statusLabels[value]}</option>)}</select></label><label className="field compact-field"><span>Дата</span><input type="date" value={date} onChange={(event) => setDate(event.target.value)} /></label></div><label className="search"><span className="sr-only">Поиск занятия</span><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Ребёнок или специалист" /></label></div>
      {lessons.isLoading ? <PageLoading /> : lessons.isError ? <Failure message={messageForError(lessons.error)} onRetry={() => void lessons.refetch()} /> : visible.length ? <LessonsTable lessons={visible} /> : <div className="empty-state"><strong>Занятий не найдено</strong><p>Измените фильтры или проведите первое занятие на планшете специалиста.</p></div>}</section>
  </>;
}

export function LessonsTable({ lessons }: { lessons: Lesson[] }) { return <div className="table-wrap"><table><thead><tr><th>Дата и время</th><th>Ребёнок</th><th>Специалист</th><th>Шаблон</th><th>Статус</th><th>Длительность</th><th>Упражнения</th><th /></tr></thead><tbody>{lessons.map((lesson) => <tr key={lesson.id}><td>{formatDate(lesson.startedAt)}</td><td>{lesson.childName}</td><td>{lesson.specialistName}</td><td>{lesson.templateName ?? "Базовое занятие Oyla"}</td><td>{lessonStatus(lesson.status)}</td><td>{formatDuration(lesson.durationMs)}</td><td>{lesson.exerciseCount}</td><td><Link className="table-link" to={`/lessons/${lesson.id}`}>Открыть</Link></td></tr>)}</tbody></table></div>; }

export function LessonDetailsPage() {
  const { lessonId = "" } = useParams(); const details = useQuery({ queryKey: ["lesson", lessonId], queryFn: () => api.getLesson(lessonId) });
  if (details.isLoading) return <PageLoading />;
  if (details.isError || !details.data) return <Failure message={details.isError ? messageForError(details.error) : "Занятие не найдено."} onRetry={() => void details.refetch()} />;
  const { lesson, exercises } = details.data;
  return <><div className="page-heading"><div><Link className="back-link" to="/lessons">← Занятия</Link><p className="eyebrow">Карточка занятия</p><h1>{lesson.childName}</h1><p className="muted">Со специалистом {lesson.specialistName}</p></div>{lessonStatus(lesson.status)}</div>
    <div className="detail-grid"><section className="panel"><h2>Сводка</h2><dl className="details"><dt>Начато</dt><dd>{formatDate(lesson.startedAt)}</dd><dt>Завершено</dt><dd>{formatDate(lesson.completedAt)}</dd><dt>Длительность</dt><dd>{formatDuration(lesson.durationMs)}</dd><dt>Шаблон</dt><dd>{lesson.templateName ?? "Базовое занятие Oyla"}</dd><dt>Упражнений</dt><dd>{lesson.exerciseCount}</dd><dt>Специалист</dt><dd>{lesson.specialistName}</dd><dt>Ребёнок</dt><dd>{lesson.childName}</dd></dl><hr /><h2>Планшеты</h2><dl className="details"><dt>Специалист</dt><dd>{details.data.specialistDeviceName}</dd><dt>Ребёнок</dt><dd>{details.data.childDeviceName}</dd></dl></section><section className="panel"><h2>Упражнения</h2>{exercises.length ? <div className="table-wrap lesson-exercise-table"><table><thead><tr><th>#</th><th>Тип / инструкция</th><th>Статус</th><th>Метрики</th></tr></thead><tbody>{exercises.map((exercise) => <tr key={exercise.position}><td>{exercise.position}</td><td><strong>{exercise.activityType === "WHITEBOARD" ? "Белая доска" : "Выбор картинки"}</strong><br />{exercise.instructionText}</td><td>{exercise.status}</td><td>{exercise.activityType === "WHITEBOARD" ? <>Длительность: {formatDuration(exercise.durationMs)}<br />Штрихов ребёнка: {exercise.childStrokeCount ?? 0}<br />Штрихов специалиста: {exercise.specialistStrokeCount ?? 0}</> : <>Попытки: {exercise.attemptCount}<br />Ошибки: {exercise.incorrectAttempts}<br />До правильного: {formatDuration(exercise.timeToCorrectMs)}</>}</td></tr>)}</tbody></table></div> : <div className="empty-inline">Данные упражнений появятся после первого показа занятия.</div>}</section></div>
  </>;
}
