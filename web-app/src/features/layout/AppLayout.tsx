import { NavLink, Outlet } from "react-router-dom";
import { useAuth } from "../../auth/AuthContext";

const nav = [
  ["/overview", "Обзор"], ["/devices", "Устройства"], ["/children", "Дети"], ["/specialists", "Специалисты"], ["/lessons", "Занятия"], ["/content/exercises", "Контент · упражнения"], ["/content/templates", "Контент · шаблоны"],
];

export function AppLayout() {
  const { center, user, role, signOut } = useAuth();
  return <div className="app-shell"><aside className="sidebar"><div className="wordmark sidebar-logo">oyla<span>•</span></div><nav>{nav.map(([path, label]) => <NavLink key={path} to={path} className={({ isActive }) => isActive ? "active" : ""}>{label}</NavLink>)}<NavLink to="/settings">Настройки</NavLink></nav><div className="center-signature"><strong>{center?.name}</strong><span>{user?.firstName} · {role ?? "—"}</span></div></aside>
    <div className="content-shell"><header className="topbar"><div><span className="mobile-mark">oyla<span>•</span></span><strong>{center?.name}</strong></div><div className="user-menu"><span>{user?.firstName} {user?.lastName ?? ""}</span><span className="role-label">{role}</span><button className="text-button" onClick={() => void signOut()}>Выйти</button></div></header><main className="main-content"><Outlet /></main></div>
  </div>;
}
