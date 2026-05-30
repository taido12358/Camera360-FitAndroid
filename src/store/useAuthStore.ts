import {create} from 'zustand';

interface User {
  id: string;
  name: string;
  email: string;
  phone: string;
  avatar?: string;
}

interface AuthState {
  user: User | null;
  isAuthenticated: boolean;
  login: (user: User) => void;
  logout: () => void;
}

export const useAuthStore = create<AuthState>(set => ({
  user: null,
  isAuthenticated: false,
  login: (user: User) => set({user, isAuthenticated: true}),
  logout: () => set({user: null, isAuthenticated: false}),
}));
