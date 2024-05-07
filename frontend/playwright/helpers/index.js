export const interceptRPC = async (page, path, jsonFilename, options = {}) => {
  const interceptConfig = {
    status: 200,
    ...options,
  };

  await page.route(`**/api/rpc/command/${path}`, async (route) => {
    await route.fulfill({
      ...interceptConfig,
      contentType: "application/transit+json",
      path: `playwright/data/${jsonFilename}`,
    });
  });
};
