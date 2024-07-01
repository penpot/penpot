import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";
import OnboardingPage from "../pages/OnboardingPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
  await DashboardPage.mockRPC(
    page,
    "get-profile",
    "logged-in-user/get-profile-logged-in.json",
  );
});

test("User can complete the onboarding", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  const onboardingPage = new OnboardingPage(page);

  await dashboardPage.goToDashboard();
  await expect(
    page.getByRole("heading", { name: "Help us get to know you" }),
  ).toBeVisible();

  await onboardingPage.fillOnboardingInputsStep1();
  await expect(
    page.getByRole("heading", { name: "Which one of these tools do" }),
  ).toBeVisible();

  await onboardingPage.fillOnboardingInputsStep2();
  await expect(
    page.getByRole("heading", { name: "Tell us about your job" }),
  ).toBeVisible();

  await onboardingPage.fillOnboardingInputsStep3();
  await expect(
    page.getByRole("heading", { name: "Where would you like to get" }),
  ).toBeVisible();

  await onboardingPage.fillOnboardingInputsStep4();
  await expect(
    page.getByRole("heading", { name: "How did you hear about Penpot?" }),
  ).toBeVisible();

  await onboardingPage.fillOnboardingInputsStep5();
  await expect(page.getByRole("button", { name: "Start" })).toBeEnabled();
});
