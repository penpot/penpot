import { test, expect } from "@playwright/test";
import DashboardPage from "../pages/DashboardPage";

test.beforeEach(async ({ page }) => {
  await DashboardPage.init(page);
});

test("The profile menu opens the Penpot changelog", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  const aboutPenpotItem = page.getByText("About Penpot");
  await aboutPenpotItem.hover();

  const changelogSubmenuItem = page.getByRole("menuitem", {
    name: "Penpot Changelog",
  });
  await expect(changelogSubmenuItem).toBeVisible();

  const changelogUrl =
    "https://github.com/penpot/penpot/blob/develop/CHANGES.md";
  await expect(changelogSubmenuItem).toHaveAttribute("data-url", changelogUrl);

  // The item opens that URL in a new tab. Abort the request in place, so the
  // suite never reaches github.com: the requested address is what proves the
  // handler wired the attribute to `open-new-window`.
  let requested = null;
  await page.context().route("https://github.com/**", (route) => {
    requested = route.request().url();
    return route.abort();
  });

  await changelogSubmenuItem.click();
  await expect.poll(() => requested).toBe(changelogUrl);
});

test("Submenu closes when hovering a menu option without submenu", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await page.getByText("About Penpot").hover();

  const changelogSubmenuItem = page.getByText("Penpot Changelog");
  await expect(changelogSubmenuItem).toBeVisible();

  await dashboardPage.userProfileOption.hover();
  await expect(changelogSubmenuItem).toBeHidden();
});

test("Submenu stays open while moving the pointer into it", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  const aboutPenpotItem = page.getByText("About Penpot");
  await aboutPenpotItem.hover();

  const changelogSubmenuItem = page.getByText("Penpot Changelog");
  await expect(changelogSubmenuItem).toBeVisible();

  // Walk the pointer from the parent option into the submenu the way a
  // real user does — gradually, crossing the gap between the two menus.
  const from = await aboutPenpotItem.boundingBox();
  const to = await changelogSubmenuItem.boundingBox();
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2);
  await page.mouse.move(to.x + to.width / 2, to.y + to.height / 2, {
    steps: 20,
  });

  await expect(changelogSubmenuItem).toBeVisible();
});

test("Submenu closes when the pointer leaves the menu entirely", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await page.getByText("About Penpot").hover();

  const changelogSubmenuItem = page.getByText("Penpot Changelog");
  await expect(changelogSubmenuItem).toBeVisible();

  await dashboardPage.mainHeading.hover();
  await expect(changelogSubmenuItem).toBeHidden();
});

test("Hovering another expandable option switches submenus", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await page.getByText("Help & Learning").hover();

  const helpCenterSubmenuItem = page.getByText("Help Center");
  await expect(helpCenterSubmenuItem).toBeVisible();

  await page.getByText("About Penpot").hover();
  await expect(page.getByText("Penpot Changelog")).toBeVisible();
  await expect(helpCenterSubmenuItem).toBeHidden();
});

test("Submenu opens with keyboard navigation", async ({ page }) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await dashboardPage.sidebarMenu
    .getByRole("menuitem", { name: "Help & Learning" })
    .press("Enter");

  await expect(page.getByText("Help Center")).toBeVisible();
});

test("Opens release notes from current version from profile menu", async ({
  page,
}) => {
  const dashboardPage = new DashboardPage(page);
  await dashboardPage.goToDashboard();

  await dashboardPage.openProfileMenu();
  await dashboardPage.clickProfileMenuItem("About Penpot");
  await expect(page.getByText("Version 0.0.0 notes")).toBeVisible();
  await dashboardPage.clickProfileMenuItem("Version");
  await expect(page.getByText("new in penpot?")).toBeVisible();
});
