import { reactive, readonly } from "vue";
import { api, ApiError } from "../api/client";
import type { CurrentUser } from "../api/types";

const state = reactive<{ user: CurrentUser | null; ready: boolean }>({ user: null, ready: false });

export const session = readonly(state);

export async function loadSession(): Promise<void> {
  try {
    state.user = await api.me();
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) console.error(error);
    state.user = null;
  } finally {
    state.ready = true;
  }
}

export function setSession(user: CurrentUser | null): void {
  state.user = user;
  state.ready = true;
}
