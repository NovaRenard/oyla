import { zodResolver } from "@hookform/resolvers/zod";
import { useState } from "react";
import { useForm } from "react-hook-form";
import { useLocation, useNavigate } from "react-router-dom";
import { z } from "zod";
import { messageForError } from "../../api/client";
import { useAuth } from "../../auth/AuthContext";

const loginSchema = z.object({ email: z.email("Введите корректный email"), password: z.string().min(1, "Введите пароль") });
type LoginValues = z.infer<typeof loginSchema>;

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
  </form></AuthShell>;
}

export function Field({ label, error, children }: { label: string; error?: string; children: React.ReactNode }) {
  return <label className="field"><span>{label}</span>{children}{error && <small className="field-error">{error}</small>}</label>;
}
