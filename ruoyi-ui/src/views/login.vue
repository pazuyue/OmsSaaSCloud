<template>
  <div class="login workspace-login">
    <el-form ref="loginForm" :model="loginForm" :rules="loginRules" class="login-form">
      <div class="login-brand"><span class="brand-mark"><i class="el-icon-box" /></span><span>OMS 工作空间</span></div>
      <h1 class="title">欢迎回来</h1><p class="login-subtitle">登录账号，开始今天的工作。</p>
      <el-form-item prop="username">
        <el-input
          v-model="loginForm.username"
          type="text"
          auto-complete="off"
          placeholder="账号" aria-label="账号"
        >
          <svg-icon slot="prefix" icon-class="user" class="el-input__icon input-icon" />
        </el-input>
      </el-form-item>
      <el-form-item prop="password">
        <el-input
          v-model="loginForm.password"
          type="password"
          auto-complete="off"
          placeholder="密码" aria-label="密码" show-password
          @keyup.enter.native="handleLogin"
        >
          <svg-icon slot="prefix" icon-class="password" class="el-input__icon input-icon" />
        </el-input>
      </el-form-item>
      <el-form-item prop="code" v-if="captchaEnabled">
        <el-input
          v-model="loginForm.code"
          auto-complete="off"
          placeholder="验证码" aria-label="验证码"
          style="width: 63%"
          @keyup.enter.native="handleLogin"
        >
          <svg-icon slot="prefix" icon-class="validCode" class="el-input__icon input-icon" />
        </el-input>
        <div class="login-code">
          <button type="button" class="captcha-button" aria-label="刷新验证码" @click="getCode"><img :src="codeUrl" class="login-code-img" alt="登录验证码" /></button>
        </div>
      </el-form-item>
      <el-checkbox v-model="loginForm.rememberMe" style="margin:0px 0px 25px 0px;">记住密码</el-checkbox>
      <el-form-item style="width:100%;">
        <el-button
          :loading="loading"
          size="medium"
          type="primary"
          style="width:100%;"
          @click.native.prevent="handleLogin"
        >
          <span v-if="!loading">登 录</span>
          <span v-else>登 录 中...</span>
        </el-button>
        <div style="float: right;" v-if="register">
          <router-link class="link-type" :to="'/register'">立即注册</router-link>
        </div>
      </el-form-item>
    </el-form>
    <!--  底部  -->
    <div class="el-login-footer">
      <span>OMS · 让业务清晰有序</span>
    </div>
  </div>
</template>

<script>
import { getCodeImg } from "@/api/login";
import Cookies from "js-cookie";
import { encrypt, decrypt } from '@/utils/jsencrypt'

export default {
  name: "Login",
  data() {
    return {
      codeUrl: "",
      loginForm: {
        username: "admin",
        password: "admin123",
        rememberMe: false,
        code: "",
        uuid: ""
      },
      loginRules: {
        username: [
          { required: true, trigger: "blur", message: "请输入您的账号" }
        ],
        password: [
          { required: true, trigger: "blur", message: "请输入您的密码" }
        ],
        code: [{ required: true, trigger: "change", message: "请输入验证码" }]
      },
      loading: false,
      // 验证码开关
      captchaEnabled: true,
      // 注册开关
      register: false,
      redirect: undefined
    };
  },
  watch: {
    $route: {
      handler: function(route) {
        this.redirect = route.query && route.query.redirect;
      },
      immediate: true
    }
  },
  created() {
    this.getCode();
    this.getCookie();
  },
  methods: {
    getCode() {
      getCodeImg().then(res => {
        this.captchaEnabled = res.captchaEnabled === undefined ? true : res.captchaEnabled;
        if (this.captchaEnabled) {
          this.codeUrl = "data:image/gif;base64," + res.img;
          this.loginForm.uuid = res.uuid;
        }
      });
    },
    getCookie() {
      const username = Cookies.get("username");
      const password = Cookies.get("password");
      const rememberMe = Cookies.get('rememberMe')
      this.loginForm = {
        username: username === undefined ? this.loginForm.username : username,
        password: password === undefined ? this.loginForm.password : decrypt(password),
        rememberMe: rememberMe === undefined ? false : Boolean(rememberMe)
      };
    },
    handleLogin() {
      this.$refs.loginForm.validate(valid => {
        if (valid) {
          this.loading = true;
          if (this.loginForm.rememberMe) {
            Cookies.set("username", this.loginForm.username, { expires: 30 });
            Cookies.set("password", encrypt(this.loginForm.password), { expires: 30 });
            Cookies.set('rememberMe', this.loginForm.rememberMe, { expires: 30 });
          } else {
            Cookies.remove("username");
            Cookies.remove("password");
            Cookies.remove('rememberMe');
          }
          this.$store.dispatch("Login", this.loginForm).then(() => {
            this.$router.push({ path: this.redirect || "/" }).catch(()=>{});
          }).catch(() => {
            this.loading = false;
            if (this.captchaEnabled) {
              this.getCode();
            }
          });
        }
      });
    }
  }
};
</script>

<style rel="stylesheet/scss" lang="scss">
.login {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 100%;
  background-image: url("../assets/images/login-background.jpg");
  background-size: cover;
}
.title {
  margin: 0px auto 30px auto;
  text-align: center;
  color: #707070;
}

.login-form {
  border-radius: 6px;
  background: #ffffff;
  width: 400px;
  padding: 25px 25px 5px 25px;
  .el-input {
    height: 38px;
    input {
      height: 38px;
    }
  }
  .input-icon {
    height: 39px;
    width: 14px;
    margin-left: 2px;
  }
}
.login-tip {
  font-size: 13px;
  text-align: center;
  color: #bfbfbf;
}
.login-code {
  width: 33%;
  height: 38px;
  float: right;
  img {
    cursor: pointer;
    vertical-align: middle;
  }
}
.el-login-footer {
  height: 40px;
  line-height: 40px;
  position: fixed;
  bottom: 0;
  width: 100%;
  text-align: center;
  color: #fff;
  font-family: Arial;
  font-size: 12px;
  letter-spacing: 1px;
}
.login-code-img {
  height: 38px;
}
</style>

<style lang="scss">
.workspace-login { min-height: 100vh; height: auto; padding: 64px 20px; background: radial-gradient(ellipse at 15% 15%, #e8f0fa, transparent 55%), radial-gradient(ellipse at 90% 85%, #eaf0f5, transparent 55%), #f6f7f9; }
.workspace-login .login-form { width: 420px; padding: 36px 38px 24px; border: 1px solid #fff; background: #ffffffed; border-radius: 20px; box-shadow: 0 20px 80px #2039560d, 0 2px 10px #20395605; }
.login-brand { display: flex; align-items: center; gap: 10px; color: #424d5c; font-size: 14px; margin-bottom: 32px; font-weight: 600; }
.workspace-login .title { text-align: left; font-size: 28px; color: #232b36; margin: 0; font-weight: 600; letter-spacing: -.8px; }
.login-subtitle { color: #7a8593; font-size: 13px; margin: 10px 0 30px; }
.workspace-login .el-input input { height: 44px; background: #fafbfd; border-radius: 9px; border-color: #e0e6ee; font-size: 13px; }
.workspace-login .el-input { height: 44px; }.workspace-login .input-icon { height: 44px; color: #94a1b3; }.workspace-login .el-checkbox__label { font-size: 12px; color: #788391; }.workspace-login .el-button { height: 43px; font-size: 14px; }.workspace-login .el-login-footer { color: #919daa; font-size: 11px; }
.workspace-login .login-code { height: 44px; }.captcha-button { padding: 0; border: 1px solid #e5e9ef; border-radius: 8px; background: #fafbfd; overflow: hidden; width: 100%; height: 44px; }.workspace-login .login-code-img { width: 100%; height: 42px; object-fit: contain; }
@media (max-width: 480px) { .workspace-login .login-form { width: 100%; padding: 30px 24px 18px; } }
</style>
