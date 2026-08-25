<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ArrowLeft, CheckCircle2, KeyRound, RefreshCw } from "@lucide/vue";
import { toast } from "../lib/notify";
import { api, captchaUrl } from "../api/client";
import { setSession } from "../stores/session";
import UiButton from "../components/ui/Button.vue";
import UiInput from "../components/ui/Input.vue";
import UiLabel from "../components/ui/Label.vue";

/** 认证卡片的业务步骤。注册和邮箱验证属于同一条连续流程。 */
type AuthMode = "login" | "register" | "verify" | "forgot" | "forgotSent" | "reset";

const route = useRoute();
const router = useRouter();

/** 根据入口地址确定首次打开的认证卡片。 */
function initialMode(): AuthMode {
  if (route.path === "/reset-password") return "reset";
  if (route.path === "/register" || route.query.mode === "register") return "register";
  return "login";
}

/** 当前认证卡片步骤。 */
const mode = ref<AuthMode>(initialMode());
/** 卡片切换方向，供左右滑动动画使用。 */
const transitionDirection = ref<"forward" | "back">("forward");
/** 当前请求是否正在处理。 */
const busy = ref(false);
/** 当前图形验证码图片地址。登录、注册和找回密码均会消费验证码。 */
const captcha = ref(captchaUrl());
/** 登录表单。 */
const login = ref({ identity: "", password: "", captcha: "" });
/** 注册表单。 */
const registration = ref({ username: "", email: "", password: "", captcha: "" });
/** 注册邮箱验证表单。 */
const verification = ref({ email: "", code: "" });
/** 找回密码表单。 */
const forgot = ref({ email: "", captcha: "" });
/** 找回密码成功后的服务端提示。 */
const forgotSentMessage = ref("");
/** 密码重置表单。 */
const reset = ref({ newPassword: "", confirmPassword: "" });

/** 邮件链接中的一次性重置令牌。 */
const resetToken = computed(() => {
  const token = route.query.token;
  return typeof token === "string" ? token : "";
});

/** 当前卡片标题。 */
const cardTitle = computed(() => ({
  login: "登录",
  register: "创建账号",
  verify: "验证邮箱",
  forgot: "找回密码",
  forgotSent: "邮件已发送",
  reset: "设置新密码",
}[mode.value]));

/** 保留受保护页面的回跳地址。 */
function authQuery(): Record<string, string> {
  const redirect = route.query.redirect;
  return typeof redirect === "string" && redirect ? { redirect } : {};
}

/** 获取新的图形验证码。 */
function refreshCaptcha(): void {
  captcha.value = captchaUrl();
}

/** 切换认证卡片，并同步入口地址。 */
async function showMode(next: AuthMode, direction: "forward" | "back" = "forward"): Promise<void> {
  transitionDirection.value = direction;
  if (next === "register") {
    await router.replace({ path: "/register", query: authQuery() });
  } else if (next === "login" || next === "forgot" || next === "forgotSent") {
    await router.replace({ path: "/login", query: authQuery() });
  }
  mode.value = next;
  if (next === "login" || next === "register" || next === "forgot") refreshCaptcha();
}

/** 从任意辅助流程返回登录卡片。 */
async function returnToLogin(): Promise<void> {
  await showMode("login", "back");
}

/** 登录并跳转回原始目标页面。 */
async function submitLogin(): Promise<void> {
  busy.value = true;
  try {
    setSession(await api.login(login.value));
    await router.push(String(route.query.redirect ?? "/problems"));
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "登录失败");
    refreshCaptcha();
  } finally {
    busy.value = false;
  }
}

/** 注册账号并进入同一卡片内的邮箱验证步骤。 */
async function submitRegistration(): Promise<void> {
  busy.value = true;
  try {
    const response = await api.register(registration.value);
    verification.value.email = registration.value.email;
    transitionDirection.value = "forward";
    mode.value = "verify";
    toast.success(response.message);
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "注册失败");
    refreshCaptcha();
  } finally {
    busy.value = false;
  }
}

/** 提交邮箱验证码，验证成功后回到登录卡片。 */
async function submitVerification(): Promise<void> {
  busy.value = true;
  try {
    const response = await api.verifyEmail(verification.value);
    login.value.identity = verification.value.email;
    toast.success(response.message);
    await showMode("login", "back");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "验证失败");
  } finally {
    busy.value = false;
  }
}

/** 使用图形验证码申请密码重置邮件。 */
async function submitForgotPassword(): Promise<void> {
  busy.value = true;
  try {
    const response = await api.requestPasswordReset(forgot.value);
    forgotSentMessage.value = response.message;
    transitionDirection.value = "forward";
    mode.value = "forgotSent";
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "邮件发送失败");
    refreshCaptcha();
  } finally {
    busy.value = false;
  }
}

/** 设置新密码并回到登录卡片。 */
async function submitPasswordReset(): Promise<void> {
  if (!resetToken.value) {
    toast.error("重置链接无效或已过期");
    return;
  }
  if (reset.value.newPassword !== reset.value.confirmPassword) {
    toast.error("两次输入的密码不一致");
    return;
  }
  busy.value = true;
  try {
    const response = await api.confirmPasswordReset({ token: resetToken.value, newPassword: reset.value.newPassword });
    toast.success(response.message);
    reset.value = { newPassword: "", confirmPassword: "" };
    await showMode("login", "back");
  } catch (error) {
    toast.error(error instanceof Error ? error.message : "密码重置失败");
  } finally {
    busy.value = false;
  }
}

/** 直接访问认证路由时同步卡片状态；内部步骤不会被路由变化打断。 */
watch(() => route.path, (path) => {
  if (path === "/reset-password") mode.value = "reset";
  else if (path === "/register" && mode.value === "login") mode.value = "register";
  else if (path === "/login" && ["register", "verify", "reset"].includes(mode.value)) mode.value = "login";
});
</script>

<template>
  <section class="auth-layout auth-layout--modern auth-layout--centered">
    <div class="auth-panel auth-card" :class="'auth-card--' + mode">
      <Transition :name="'auth-card-' + transitionDirection" mode="out-in">
        <div :key="mode" class="auth-card-view">
          <header class="auth-card-header">
            <button v-if="mode !== 'login'" class="auth-back-button" type="button" title="返回登录" aria-label="返回登录" @click="returnToLogin">
              <ArrowLeft :size="19" />
            </button>
            <div class="auth-card-title">
              <KeyRound v-if="mode === 'forgot' || mode === 'forgotSent' || mode === 'reset'" :size="20" aria-hidden="true" />
              <CheckCircle2 v-else-if="mode === 'verify'" :size="20" aria-hidden="true" />
              <h1>{{ cardTitle }}</h1>
            </div>
          </header>

          <form v-if="mode === 'login'" class="auth-form" @submit.prevent="submitLogin">
            <div class="auth-field"><UiLabel for="login-identity">用户名或邮箱</UiLabel><UiInput id="login-identity" v-model="login.identity" autocomplete="username" /></div>
            <div class="auth-field"><UiLabel for="login-password">密码</UiLabel><UiInput id="login-password" v-model="login.password" type="password" autocomplete="current-password" /></div>
            <div class="auth-field">
              <UiLabel for="login-captcha">图形验证码</UiLabel>
              <div class="captcha-row"><UiInput id="login-captcha" v-model="login.captcha" maxlength="5" autocomplete="off" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" aria-label="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div>
            </div>
            <div class="auth-form-links"><button class="auth-text-action" type="button" @click="showMode('forgot')">忘记密码？</button></div>
            <UiButton type="submit" :loading="busy" class="w-full">登录</UiButton>
            <footer class="auth-card-footer"><span>还没有账号？</span><button class="auth-text-action" type="button" @click="showMode('register')">创建账号</button></footer>
          </form>

          <form v-else-if="mode === 'register'" class="auth-form" @submit.prevent="submitRegistration">
            <div class="auth-field"><UiLabel for="register-username">用户名</UiLabel><UiInput id="register-username" v-model="registration.username" autocomplete="username" /></div>
            <div class="auth-field"><UiLabel for="register-email">邮箱</UiLabel><UiInput id="register-email" v-model="registration.email" type="email" autocomplete="email" /></div>
            <div class="auth-field"><UiLabel for="register-password">密码</UiLabel><UiInput id="register-password" v-model="registration.password" type="password" autocomplete="new-password" /></div>
            <div class="auth-field">
              <UiLabel for="register-captcha">图形验证码</UiLabel>
              <div class="captcha-row"><UiInput id="register-captcha" v-model="registration.captcha" maxlength="5" autocomplete="off" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" aria-label="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div>
            </div>
            <UiButton type="submit" :loading="busy" class="w-full">继续</UiButton>
          </form>

          <form v-else-if="mode === 'verify'" class="auth-form" @submit.prevent="submitVerification">
            <p class="auth-card-message">验证码已发送至 <strong>{{ verification.email }}</strong></p>
            <div class="auth-field"><UiLabel for="verification-email">邮箱</UiLabel><UiInput id="verification-email" v-model="verification.email" type="email" readonly /></div>
            <div class="auth-field"><UiLabel for="verification-code">六位验证码</UiLabel><UiInput id="verification-code" v-model="verification.code" maxlength="6" inputmode="numeric" autocomplete="one-time-code" /></div>
            <UiButton type="submit" :loading="busy" class="w-full">完成验证</UiButton>
          </form>

          <form v-else-if="mode === 'forgot'" class="auth-form" @submit.prevent="submitForgotPassword">
            <p class="auth-card-message">输入已验证的邮箱，我们会发送密码重置链接。</p>
            <div class="auth-field"><UiLabel for="forgot-email">邮箱</UiLabel><UiInput id="forgot-email" v-model="forgot.email" type="email" autocomplete="email" /></div>
            <div class="auth-field">
              <UiLabel for="forgot-captcha">图形验证码</UiLabel>
              <div class="captcha-row"><UiInput id="forgot-captcha" v-model="forgot.captcha" maxlength="5" autocomplete="off" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" aria-label="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div>
            </div>
            <UiButton type="submit" :loading="busy" class="w-full">发送重置邮件</UiButton>
          </form>

          <div v-else-if="mode === 'forgotSent'" class="auth-form auth-result">
            <CheckCircle2 :size="38" aria-hidden="true" />
            <p class="auth-card-message">{{ forgotSentMessage }}</p>
            <UiButton type="button" class="w-full" @click="returnToLogin">返回登录</UiButton>
          </div>

          <form v-else class="auth-form" @submit.prevent="submitPasswordReset">
            <p class="auth-card-message" :class="{ 'auth-card-message--error': !resetToken }">{{ resetToken ? "设置一个新的登录密码。" : "重置链接无效或已过期，请重新申请。" }}</p>
            <div class="auth-field"><UiLabel for="reset-password">新密码</UiLabel><UiInput id="reset-password" v-model="reset.newPassword" type="password" autocomplete="new-password" :disabled="!resetToken" /></div>
            <div class="auth-field"><UiLabel for="reset-password-confirm">确认新密码</UiLabel><UiInput id="reset-password-confirm" v-model="reset.confirmPassword" type="password" autocomplete="new-password" :disabled="!resetToken" /></div>
            <UiButton type="submit" :loading="busy" class="w-full" :disabled="!resetToken">保存新密码</UiButton>
          </form>
        </div>
      </Transition>
    </div>
  </section>
</template>
