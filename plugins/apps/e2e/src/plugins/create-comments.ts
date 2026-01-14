export default function () {
  async function createComment() {
    const page = penpot.currentPage;

    if (page) {
      await page.addCommentThread('Hello world!', {
        x: penpot.viewport.center.x,
        y: penpot.viewport.center.y,
      });
    }
  }

  async function replyComment() {
    const page = penpot.currentPage;

    if (page) {
      const comments = await page.findCommentThreads({
        onlyYours: true,
        showResolved: false,
      });
      await comments[0].reply('This is a reply.');
    }
  }

  async function deleteComment() {
    const page = penpot.currentPage;

    if (page) {
      const commentThreads = await page.findCommentThreads({
        onlyYours: true,
        showResolved: false,
      });
      await page.removeCommentThread(commentThreads[0]);
    }
  }

  createComment();
  replyComment();
  deleteComment();
}
