import { createContext, useContext, useMemo, useState, type PropsWithChildren } from "react";

type Toast = { id: number; message: string; tone: "success" | "error" };
const ToastContext = createContext<{ show(message: string, tone?: Toast["tone"]): void } | null>(null);

export function ToastProvider({ children }: PropsWithChildren) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const value = useMemo(() => ({ show(message: string, tone: Toast["tone"] = "success") {
    const id = Date.now();
    setToasts((items) => [...items, { id, message, tone }]);
    window.setTimeout(() => setToasts((items) => items.filter((item) => item.id !== id)), 4500);
  } }), []);
  return <ToastContext.Provider value={value}>{children}<div className="toast-stack" aria-live="polite">{toasts.map((toast) => <div className={`toast ${toast.tone}`} key={toast.id}>{toast.message}</div>)}</div></ToastContext.Provider>;
}
export const useToast = () => {
  const value = useContext(ToastContext);
  if (!value) throw new Error("useToast должен использоваться внутри ToastProvider");
  return value;
};
