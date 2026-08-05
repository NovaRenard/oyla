import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { Link, useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { messageForError } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";

const loginSchema = z.object({ email: z.email("Введите корректный email"), password: z.string().min(1, "Введите пароль") });
const registerSchema = z.object({
  centerName: z.string().trim().min(2, "Укажите название центра").max(160),
  firstName: z.string().trim().min(1, "Укажите имя").max(100),
  lastName: z.string().trim().max(100).optional(),
  email: z.email("Введите корректный email"),
  password: z.string().min(8, "Пароль должен содержать не менее 8 символов"),
  passwordConfirmation: z.string(),
}).refine((data) => data.password === data.passwordConfirmation, { message: "Пароли не совпадают", path: ["passwordConfirmation"] });

type LoginValues = z.infer<typeof loginSchema>;
type RegisterValues = z.infer<typeof registerSchema>;

function AuthShell({ children, title, subtitle }: { children: React.ReactNode; title: string; subtitle: string }) {
  return <main className="auth-page"><section className="auth-brand"><div className="wordmark">oyla<span>•</span></div><p>Простая среда для бережной работы с детьми.</p></section><section className="auth-card"><p className="eyebrow">Кабинет центра</p><h1>{title}</h1><p className="muted">{subtitle}</p>{children}</section></main>;
}

export function LoginPage() {
  const { signIn } = useAuth(); const navigate = useNavigate(); const location = useLocation(); const [serverError, setServerError] = useState<string>();
  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: "", password: "" } });
  const submit = form.handleSubmit(async (values) => {
    setServerError(undefined);
    try { await signIn(values.email, values.password); navigate((location.state as { from?: string } | null)?.from ?? "/overview", { replace: true }); }
    catch (error) { setServerError(messageForError(error)); }
  });
  return <AuthShell title="Войдите в кабинет" subtitle="Используйте рабочий email центра."><form onSubmit={submit} noValidate className="form-stack">
    <Field label="Email" error={form.formState.errors.email?.message}><input autoComplete="email" type="email" {...form.register("email")} /></Field>
    <Field label="Пароль" error={form.formState.errors.password?.message}><input autoComplete="current-password" type="password" {...form.register("password")} /></Field>
    {serverError && <div className="form-error" role="alert">{serverError}</div>}
    <button className="primary-button" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? "Входим…" : "Войти"}</button>
    <p className="auth-switch">Нет центра? <Link to="/register">Зарегистрироваться</Link></p>
  </form></AuthShell>;
}

export function RegisterPage() {
  const { register: registerCenter } = useAuth(); const navigate = useNavigate(); const [serverError, setServerError] = useState<string>();
  const form = useForm<RegisterValues>({ resolver: zodResolver(registerSchema), defaultValues: { centerName: "", firstName: "", lastName: "", email: "", password: "", passwordConfirmation: "" } });
  const submit = form.handleSubmit(async ({ passwordConfirmation: _confirmation, ...values }) => {
    setServerError(undefined);
    try { await registerCenter(values); navigate("/overview", { replace: true }); } catch (error) { setServerError(messageForError(error)); }
  });
  return <AuthShell title="Создайте кабинет центра" subtitle="Это займёт меньше минуты."><form onSubmit={submit} noValidate className="form-stack">
    <Field label="Название центра" error={form.formState.errors.centerName?.message}><input autoComplete="organization" {...form.register("centerName")} /></Field>
    <div className="form-grid"><Field label="Имя" error={form.formState.errors.firstName?.message}><input autoComplete="given-name" {...form.register("firstName")} /></Field><Field label="Фамилия (необязательно)" error={form.formState.errors.lastName?.message}><input autoComplete="family-name" {...form.register("lastName")} /></Field></div>
    <Field label="Email" error={form.formState.errors.email?.message}><input autoComplete="email" type="email" {...form.register("email")} /></Field>
    <div className="form-grid"><Field label="Пароль" error={form.formState.errors.password?.message}><input autoComplete="new-password" type="password" {...form.register("password")} /></Field><Field label="Повторите пароль" error={form.formState.errors.passwordConfirmation?.message}><input autoComplete="new-password" type="password" {...form.register("passwordConfirmation")} /></Field></div>
    {serverError && <div className="form-error" role="alert">{serverError}</div>}
    <button className="primary-button" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? "Создаём…" : "Создать центр"}</button>
    <p className="auth-switch">Уже есть кабинет? <Link to="/login">Войти</Link></p>
  </form></AuthShell>;
}

export function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return <label className="field"><span>{label}</span>{children}{error && <small className="field-error">{error}</small>}</label>;
}
