import { Navigate, Outlet, Route, Routes, useLocation } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { AppLayout } from "./features/layout/AppLayout";
import { LoginPage, RegisterPage } from "./features/auth/AuthPages";
import { DashboardPage } from "./features/dashboard/DashboardPage";
import { DeviceDetailsPage, DevicesPage } from "./features/devices/DevicesPages";
import { SettingsPage } from "./features/settings/SettingsPage";

function ProtectedRoute() {
  const { loading, user } = useAuth();
  const location = useLocation();
  if (loading) return <main className="page-loading">Загружаем кабинет…</main>;
  return user ? <Outlet /> : <Navigate to="/login" replace state={{ from: location.pathname }} />;
}

function GuestRoute() {
  const { loading, user } = useAuth();
  if (loading) return <main className="page-loading">Загружаем…</main>;
  return user ? <Navigate to="/overview" replace /> : <Outlet />;
}

export function App() {
  return <Routes>
    <Route element={<GuestRoute />}><Route path="/login" element={<LoginPage />} /><Route path="/register" element={<RegisterPage />} /></Route>
    <Route element={<ProtectedRoute />}><Route element={<AppLayout />}>
      <Route path="/" element={<Navigate to="/overview" replace />} />
      <Route path="/overview" element={<DashboardPage />} />
      <Route path="/devices" element={<DevicesPage />} />
      <Route path="/devices/:deviceId" element={<DeviceDetailsPage />} />
      <Route path="/settings" element={<SettingsPage />} />
    </Route></Route>
    <Route path="*" element={<Navigate to="/overview" replace />} />
  </Routes>;
}
