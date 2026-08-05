import { createContext, useContext, useEffect, useMemo, useState, type PropsWithChildren } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { api, subscribeToSessionEnd } from "../api/client";
import type { AuthResponse, Center, Membership, User } from "../api/types";

type AuthContextValue = {
  loading: boolean;
  user: User | null;
  center: Center | null;
  role: Membership["role"] | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

function profileFrom(auth: Pick<AuthResponse, "user" | "centers" | "activeCenter">) {
  const center = auth.activeCenter ?? auth.centers[0]?.center ?? null;
  return { user: auth.user, center, role: center ? auth.centers.find((item) => item.center.id === center.id)?.role ?? null : null };
}

export function AuthProvider({ children }: PropsWithChildren) {
  const queryClient = useQueryClient();
  const [loading, setLoading] = useState(true);
  const [profile, setProfile] = useState<ReturnType<typeof profileFrom> | null>(null);
  useEffect(() => {
    const unsubscribe = subscribeToSessionEnd(() => {
      // Protected data must not remain visible after a failed cookie refresh or logout.
      void queryClient.cancelQueries();
      queryClient.clear();
      setProfile(null);
    });
    api.restore().then((me) => setProfile(me ? profileFrom(me) : null)).finally(() => setLoading(false));
    return unsubscribe;
  }, [queryClient]);
  const value = useMemo<AuthContextValue>(() => ({
    loading,
    user: profile?.user ?? null,
    center: profile?.center ?? null,
    role: profile?.role ?? null,
    async signIn(email, password) { setProfile(profileFrom(await api.login(email, password))); },
    async signOut() { await api.logout(); setProfile(null); },
  }), [loading, profile]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth должен использоваться внутри AuthProvider");
  return value;
}
