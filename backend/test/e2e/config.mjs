const config = Object.freeze({
  baseUrl: process.env.PENPOT_BASE_URL || "http://localhost:3450",
  email: process.env.PENPOT_EMAIL || null,
  password: process.env.PENPOT_PASSWORD || null,
});

export default config;
