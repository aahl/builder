import { create } from "zustand";
import Cookies from "@react-native-cookies/cookies";
import { api } from "@/services/api";
import { useSettingsStore } from "./settingsStore";

interface AuthState {
  isLoggedIn: boolean;
  isLoginModalVisible: boolean;
  showLoginModal: () => void;
  hideLoginModal: () => void;
  checkLoginStatus: (apiBaseUrl?: string) => Promise<void>;
  logout: () => Promise<void>;
}

const useAuthStore = create<AuthState>((set) => ({
  isLoggedIn: false,
  isLoginModalVisible: false,
  showLoginModal: () => set({ isLoginModalVisible: true }),
  hideLoginModal: () => set({ isLoginModalVisible: false }),
  checkLoginStatus: async (apiBaseUrl?: string) => {
    if (!apiBaseUrl) {
      set({ isLoggedIn: false, isLoginModalVisible: false });
      return;
    }
    try {
      // 冷启动时 serverConfig 可能仍在请求中，先等一次，避免把"配置未就绪"误判为需要登录
      let serverConfig = useSettingsStore.getState().serverConfig;
      if (!serverConfig) {
        await useSettingsStore.getState().fetchServerConfig();
        serverConfig = useSettingsStore.getState().serverConfig;
      }
      if (!serverConfig) {
        // 配置拉取失败（网络或地址不可用），不弹登录框，避免无密码服务器被误报
        set({ isLoggedIn: false });
        return;
      }
      const cookies = await Cookies.get(api.baseURL);
      if (serverConfig && serverConfig.StorageType === "localstorage" && !cookies.auth) {
        const loginResult = await api.login().catch(() => {
          set({ isLoggedIn: false, isLoginModalVisible: true });
        });
        if (loginResult && loginResult.ok) {
          set({ isLoggedIn: true });
        }
      } else {
        const isLoggedIn = cookies && !!cookies.auth;
        set({ isLoggedIn });
        if (!isLoggedIn) {
          set({ isLoginModalVisible: true });
        }
      }
    } catch (error) {
      console.info("Failed to check login status:", error);
      if (error instanceof Error && error.message === "UNAUTHORIZED") {
        set({ isLoggedIn: false, isLoginModalVisible: true });
      } else {
        set({ isLoggedIn: false });
      }
    }
  },
  logout: async () => {
    try {
      await Cookies.clearAll();
      set({ isLoggedIn: false, isLoginModalVisible: true });
    } catch (error) {
      console.info("Failed to logout:", error);
    }
  },
}));

export default useAuthStore;
