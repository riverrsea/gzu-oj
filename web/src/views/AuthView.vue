<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { RefreshCw } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api, captchaUrl } from "../api/client";
import { setSession } from "../stores/session";
import UiButton from "../components/ui/Button.vue";
import UiInput from "../components/ui/Input.vue";
import UiLabel from "../components/ui/Label.vue";

const route = useRoute();
const router = useRouter();
const mode = ref<"login" | "register" | "verify">("login");
const captcha = ref(captchaUrl());
const busy = ref(false);
const login = ref({ identity: "", password: "", captcha: "" });
const registration = ref({ username: "", email: "", password: "", captcha: "" });
const verification = ref({ email: "", code: "" });

function refreshCaptcha(): void { captcha.value = captchaUrl(); }

async function submitLogin(): Promise<void> {
  busy.value = true;
  try {
    setSession(await api.login(login.value));
    await router.push(String(route.query.redirect ?? "/problems"));
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "登录失败");
    refreshCaptcha();
  } finally { busy.value = false; }
}

async function submitRegistration(): Promise<void> {
  busy.value = true;
  try {
    const response = await api.register(registration.value);
    verification.value.email = registration.value.email;
    mode.value = "verify";
    ElMessage.success(response.message);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "注册失败");
    refreshCaptcha();
  } finally { busy.value = false; }
}

async function submitVerification(): Promise<void> {
  busy.value = true;
  try {
    const response = await api.verifyEmail(verification.value);
    ElMessage.success(response.message);
    login.value.identity = verification.value.email;
    mode.value = "login";
    refreshCaptcha();
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "验证失败");
  } finally { busy.value = false; }
}
</script>

<template>
  <section class="auth-layout auth-layout--modern">
    <div class="auth-copy">
      <img src="/brand-mark.svg" alt="" />
      <h1>研试 OJ</h1>
      <p>历年复试真题与个人练习判题</p>
    </div>
    <div class="auth-panel">
      <div class="segmented" role="tablist">
        <button :class="{ active: mode === 'login' }" @click="mode = 'login'">登录</button>
        <button :class="{ active: mode === 'register' }" @click="mode = 'register'">注册</button>
        <button :class="{ active: mode === 'verify' }" @click="mode = 'verify'">验证邮箱</button>
      </div>
      <form v-if="mode === 'login'" class="grid gap-5" @submit.prevent="submitLogin">
        <div class="grid gap-2"><UiLabel for="login-identity">用户名或邮箱</UiLabel><UiInput id="login-identity" v-model="login.identity" autocomplete="username" /></div>
        <div class="grid gap-2"><UiLabel for="login-password">密码</UiLabel><UiInput id="login-password" v-model="login.password" type="password" autocomplete="current-password" /></div>
        <div class="grid gap-2"><UiLabel for="login-captcha">图形验证码</UiLabel><div class="captcha-row"><UiInput id="login-captcha" v-model="login.captcha" maxlength="5" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div></div>
        <UiButton type="submit" :loading="busy" class="w-full">登录</UiButton>
      </form>
      <form v-else-if="mode === 'register'" class="grid gap-5" @submit.prevent="submitRegistration">
        <div class="grid gap-2"><UiLabel for="register-username">用户名</UiLabel><UiInput id="register-username" v-model="registration.username" autocomplete="username" /></div>
        <div class="grid gap-2"><UiLabel for="register-email">邮箱</UiLabel><UiInput id="register-email" v-model="registration.email" type="email" autocomplete="email" /></div>
        <div class="grid gap-2"><UiLabel for="register-password">密码</UiLabel><UiInput id="register-password" v-model="registration.password" type="password" autocomplete="new-password" /></div>
        <div class="grid gap-2"><UiLabel for="register-captcha">图形验证码</UiLabel><div class="captcha-row"><UiInput id="register-captcha" v-model="registration.captcha" maxlength="5" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div></div>
        <UiButton type="submit" :loading="busy" class="w-full">创建账号</UiButton>
      </form>
      <form v-else class="grid gap-5" @submit.prevent="submitVerification">
        <div class="grid gap-2"><UiLabel for="verification-email">邮箱</UiLabel><UiInput id="verification-email" v-model="verification.email" type="email" /></div>
        <div class="grid gap-2"><UiLabel for="verification-code">六位验证码</UiLabel><UiInput id="verification-code" v-model="verification.code" maxlength="6" inputmode="numeric" /></div>
        <UiButton type="submit" :loading="busy" class="w-full">完成验证</UiButton>
      </form>
    </div>
  </section>
</template>
