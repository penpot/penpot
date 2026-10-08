import test from "node:test";
import assert from "node:assert/strict";

import {
  allLinks,
  buildDecisionsRequest,
  DECISION_QUESTIONS,
  decisionState,
  deterministicChecks,
  projectionText,
  scoreUrl,
} from "./analyze.mjs";

const projection = {
  projectionVersion: 1,
  fileId: "00000000-0000-0000-0000-000000000001",
  fileName: "phish",
  pages: [
    {
      name: "Login",
      frames: [
        {
          name: "Login form",
          text: ["Your account has been suspended.", "Enter your password to continue."],
          links: [{ text: "Verify", url: "https://evil.example/verify" }],
        },
      ],
    },
  ],
};

test("projectionText gathers page, frame and text content", () => {
  const text = projectionText(projection);
  assert.match(text, /login form/);
  assert.match(text, /your account has been suspended/);
});

test("allLinks collects links from pages and frames", () => {
  assert.deepEqual(allLinks(projection), [
    { text: "Verify", url: "https://evil.example/verify" },
  ]);
});

test("scoreUrl flags a raw IP host", () => {
  const result = scoreUrl("http://192.168.0.10/login");
  assert.ok(result.score >= 2);
  assert.ok(result.reasons.some((r) => r.includes("IP")));
});

test("scoreUrl flags a punycode host", () => {
  const result = scoreUrl("https://xn--pypal-4ve.example/login");
  assert.ok(result.score >= 3);
  assert.ok(result.reasons.some((r) => r.includes("punycode")));
});

test("scoreUrl flags a brand token outside its official domain", () => {
  const result = scoreUrl("https://paypal.com.evil.example/verify");
  assert.ok(result.score >= 2);
  assert.ok(result.reasons.some((r) => r.includes("paypal")));
});

test("scoreUrl does not flag the official brand domain", () => {
  const result = scoreUrl("https://paypal.com/login");
  assert.ok(!result.reasons.some((r) => r.includes("brand token")));
});

test("scoreUrl flags a suspicious TLD", () => {
  const result = scoreUrl("https://secure.example.xyz/account");
  assert.ok(result.reasons.some((r) => r.includes("TLD")));
});

test("scoreUrl tolerates an unparseable url", () => {
  assert.deepEqual(scoreUrl("not a url"), { score: 0, reasons: ["unparseable url"] });
});

test("deterministicChecks surfaces credential, urgency and URL signals", () => {
  const checks = deterministicChecks(projection);
  assert.deepEqual(checks.requests_credentials, ["password"]);
  assert.ok(checks.uses_urgency.includes("suspended"));
  assert.equal(checks.links.length, 1);
  assert.equal(checks.suspicious_urls.length, 0);
});

test("deterministicChecks marks a raw-IP link as suspicious", () => {
  const checks = deterministicChecks({
    pages: [{ name: "P", frames: [{ name: "F", text: [], links: [{ text: "go", url: "http://10.0.0.1/x" }] }] }],
  });
  assert.equal(checks.suspicious_urls.length, 1);
});

test("decisionState wraps the projection and the checks under named fields", () => {
  const checks = deterministicChecks(projection);
  const state = decisionState(projection, checks);
  assert.equal(state.design, projection);
  assert.equal(state.deterministic_checks, checks);
});

test("DECISION_QUESTIONS are noul questions with aligned true/false criteria", () => {
  const keys = Object.keys(DECISION_QUESTIONS);
  assert.ok(keys.includes("is_phishing"));
  assert.ok(keys.includes("is_malicious"));
  for (const [name, question] of Object.entries(DECISION_QUESTIONS)) {
    assert.equal(question.type, "noul", `${name} is a noul`);
    assert.equal(typeof question.instructions, "string");
    assert.ok(question.instructions.length > 0, `${name} has instructions`);
    assert.equal(typeof question.criteria.true, "string", `${name} has true criteria`);
    assert.equal(typeof question.criteria.false, "string", `${name} has false criteria`);
  }
});

test("buildDecisionsRequest assembles the OpenRouter decisions payload", () => {
  const checks = deterministicChecks(projection);
  const request = buildDecisionsRequest("upstage/solar-decide-flash", projection, checks);
  assert.equal(request.model, "upstage/solar-decide-flash");
  assert.equal(request.questions, DECISION_QUESTIONS);
  assert.deepEqual(request.state, { design: projection, deterministic_checks: checks });
});
