<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { RefreshCw } from "@lucide/vue";
import { ElMessage } from "element-plus";
import { api, captchaUrl } from "../api/client";
import { setSession } from "../stores/session";

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
      <el-form v-if="mode === 'login'" label-position="top" @submit.prevent="submitLogin">
        <el-form-item label="用户名或邮箱"><el-input v-model="login.identity" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="login.password" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-form-item label="图形验证码">
          <div class="captcha-row"><el-input v-model="login.captcha" maxlength="5" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div>
        </el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">登录</el-button>
      </el-form>
      <el-form v-else-if="mode === 'register'" label-position="top" @submit.prevent="submitRegistration">
        <el-form-item label="用户名"><el-input v-model="registration.username" autocomplete="username" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="registration.email" type="email" autocomplete="email" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="registration.password" type="password" show-password autocomplete="new-password" /></el-form-item>
        <el-form-item label="图形验证码"><div class="captcha-row"><el-input v-model="registration.captcha" maxlength="5" /><img :src="captcha" alt="图形验证码" /><button class="icon-button" type="button" title="刷新验证码" @click="refreshCaptcha"><RefreshCw :size="18" /></button></div></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">创建账号</el-button>
      </el-form>
      <el-form v-else label-position="top" @submit.prevent="submitVerification">
        <el-form-item label="邮箱"><el-input v-model="verification.email" type="email" /></el-form-item>
        <el-form-item label="六位验证码"><el-input v-model="verification.code" maxlength="6" inputmode="numeric" /></el-form-item>
        <el-button native-type="submit" type="primary" :loading="busy">完成验证</el-button>
      </el-form>
    </div>
  </section>
</template>
