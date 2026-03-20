import { FileRpc } from '../models/file-rpc.model';
const apiUrl = 'https://localhost:3449';

export async function PenpotApi() {
  if (!process.env['E2E_LOGIN_EMAIL']) {
    throw new Error('E2E_LOGIN_EMAIL not set');
  }

  const body = JSON.stringify({
    email: process.env['E2E_LOGIN_EMAIL'],
    password: process.env['E2E_LOGIN_PASSWORD'],
  });

  const resultLoginRequest = await fetch(
    `${apiUrl}/api/main/methods/login-with-password`,
    {
      credentials: 'include',
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
      body: body,
    },
  );

  const loginData = await resultLoginRequest.json();

  const authToken = resultLoginRequest.headers
    .getSetCookie()
    .find((cookie: string) => cookie.startsWith('auth-token='))
    ?.split(';')[0];

  if (!authToken) {
    throw new Error('Login failed');
  }

  return {
    getAuth: () => authToken,
    createFile: async () => {
      const createFileRequest = await fetch(
        `${apiUrl}/api/main/methods/create-file`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/transit+json',
            cookie: authToken,
            credentials: 'include',
          },
          body: JSON.stringify({
            '~:name': `test file ${new Date().toISOString()}`,
            '~:project-id': loginData['~:default-project-id'],
            '~:features': {
              '~#set': [
                'fdata/objects-map',
                'fdata/pointer-map',
                'fdata/shape-data-type',
                'fdata/path-data',
                'design-tokens/v1',
                'variants/v1',
                'components/v2',
                'styles/v2',
                'layout/grid',
                'plugins/runtime',
              ],
            },
          }),
        },
      );

      const fileData = (await createFileRequest.json()) as FileRpc;
      console.log('File data received:', fileData);
      return fileData;
    },
    deleteFile: async (fileId: string) => {
      const deleteFileRequest = await fetch(
        `${apiUrl}/api/main/methods/delete-file`,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/transit+json',
            cookie: authToken,
            credentials: 'include',
          },
          body: JSON.stringify({
            '~:id': fileId,
          }),
        },
      );

      return deleteFileRequest;
    },
  };
}
