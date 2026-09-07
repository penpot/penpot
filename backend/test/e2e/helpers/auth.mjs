import { rpcPost, extractCookie } from "./client.mjs";

export async function createDemoProfile() {
  const res = await rpcPost("create-demo-profile", {});
  if (res.body.type === "validation" || res.body.type === "restriction") {
    throw new Error(
      `Failed to create demo profile: ${res.body.code} - ${res.body.hint || ""}`
    );
  }
  return { email: res.body.email, password: res.body.password };
}

export async function login(email, password) {
  const res = await rpcPost("login-with-password", { email, password });
  if (res.status !== 200 || res.body.type) {
    throw new Error(
      `Login failed: ${JSON.stringify(res.body)}`
    );
  }
  const cookie = extractCookie(res.setCookie);
  return { profile: res.body, cookie };
}

export async function createAccessToken(cookie, name = "e2e-test-token") {
  const res = await rpcPost("create-access-token", { name }, { cookieToken: cookie });
  if (res.status !== 200 || res.body.type) {
    throw new Error(
      `Create access token failed: ${JSON.stringify(res.body)}`
    );
  }
  return res.body;
}

export async function setupTestProfile() {
  const { email, password } = await createDemoProfile();
  const { profile, cookie } = await login(email, password);
  return { profile, cookie, email, password };
}
