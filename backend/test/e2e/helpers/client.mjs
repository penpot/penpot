import config from "../config.mjs";

async function parseResponse(response) {
  const contentType = response.headers.get("content-type") || "";
  const setCookie = response.headers.get("set-cookie") || null;

  let body;
  if (contentType.includes("application/json")) {
    body = await response.json();
  } else {
    body = await response.text();
  }

  return {
    status: response.status,
    headers: response.headers,
    body,
    setCookie,
  };
}

export function extractCookie(setCookieHeader, name = "auth-token") {
  if (!setCookieHeader) return null;
  const match = setCookieHeader.match(new RegExp(`${name}=([^;]+)`));
  return match ? match[1] : null;
}

export async function rpcPost(method, body = {}, { cookieToken, accessToken } = {}) {
  const headers = {
    "Content-Type": "application/json",
    Accept: "application/json",
  };
  if (cookieToken) {
    headers.Cookie = `auth-token=${cookieToken}`;
  }
  if (accessToken) {
    headers.Authorization = `Token ${accessToken}`;
  }

  const response = await fetch(`${config.baseUrl}/api/main/methods/${method}`, {
    method: "POST",
    headers,
    body: JSON.stringify(body),
  });

  return parseResponse(response);
}

export async function multipartPost(method, formData, { cookieToken } = {}) {
  const headers = {
    Accept: "application/json",
  };
  if (cookieToken) {
    headers.Cookie = `auth-token=${cookieToken}`;
  }

  const response = await fetch(`${config.baseUrl}/api/main/methods/${method}`, {
    method: "POST",
    headers,
    body: formData,
  });

  return parseResponse(response);
}

export async function getAsset(
  id,
  { cookieToken, accessToken, redirect = "manual" } = {}
) {
  const headers = { Accept: "application/json" };
  if (cookieToken) {
    headers.Cookie = `auth-token=${cookieToken}`;
  }
  if (accessToken) {
    headers.Authorization = `Token ${accessToken}`;
  }

  const response = await fetch(`${config.baseUrl}/assets/by-id/${id}`, {
    method: "GET",
    headers,
    redirect,
  });

  return parseResponse(response);
}

