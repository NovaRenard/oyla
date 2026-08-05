import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { useForm } from "react-hook-form";
import { z } from "zod";
import { api, messageForError } from "../../api/client";
import { useToast } from "../../ui/Toast";
import { Failure, PageLoading } from "../dashboard/DashboardPage";

const schema = z.object({ name: z.string().trim().min(2, "Укажите название центра").max(160), timezone: z.string().trim().min(1, "Укажите часовой пояс") });
type Form = z.infer<typeof schema>;

export function SettingsPage() {
  const toast = useToast(); const queryClient = useQueryClient(); const center = useQuery({ queryKey: ["current-center"], queryFn: api.currentCenter });
  const form = useForm<Form>({ resolver: zodResolver(schema) });
  useEffect(() => { if (center.data) form.reset({ name: center.data.name, timezone: center.data.timezone }); }, [center.data, form]);
  const update = useMutation({ mutationFn: api.updateCenter, onSuccess: (result) => { queryClient.setQueryData(["current-center"], result); toast.show("Настройки центра сохранены"); }, onError: (error) => toast.show(messageForError(error), "error") });
  if (center.isLoading) return <PageLoading />;
  if (center.isError) return <Failure message={messageForError(center.error)} onRetry={() => void center.refetch()} />;
  return <><div className="page-heading"><div><p className="eyebrow">Настройки</p><h1>Центр</h1><p className="muted">Основные сведения и часовой пояс.</p></div></div><section className="panel narrow"><form className="form-stack" onSubmit={form.handleSubmit((value) => update.mutate(value))} noValidate><label className="field"><span>Название центра</span><input {...form.register("name")} />{form.formState.errors.name?.message && <small className="field-error">{form.formState.errors.name.message}</small>}</label><label className="field"><span>Часовой пояс</span><input {...form.register("timezone")} />{form.formState.errors.timezone?.message && <small className="field-error">{form.formState.errors.timezone.message}</small>}</label><button className="primary-button" disabled={update.isPending}>{update.isPending ? "Сохраняем…" : "Сохранить"}</button></form></section></>;
}
