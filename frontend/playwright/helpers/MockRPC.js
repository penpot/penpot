export const interceptRPC = (page, path, jsonFilename) =>
  page.route(`**/api/rpc/command/${path}`, (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/transit+json",
      path: `playwright/fixtures/${jsonFilename}`,
    })
  );
