export const interceptRPC = async (page, path, jsonFilename) => {
  await page.route(`**/api/rpc/command/${path}`, (route) => {
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: `playwright/fixtures/${jsonFilename}`,
    });
  });
};
