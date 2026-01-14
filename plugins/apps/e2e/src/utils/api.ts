import { FileRpc } from '../models/file-rpc.model';

const apiUrl = 'http://localhost:3449';

export async function PenpotApi() {
  if (!process.env['E2E_LOGIN_EMAIL']) {
    throw new Error('E2E_LOGIN_EMAIL not set');
  }

  const resultLoginRequest = await fetch(
    `${apiUrl}/api/rpc/command/login-with-password`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/transit+json',
      },
      body: JSON.stringify({
        '~:email': process.env['E2E_LOGIN_EMAIL'],
        '~:password': process.env['E2E_LOGIN_PASSWORD'],
      }),
    },
  );

  const loginData = await resultLoginRequest.json();
  const authToken = resultLoginRequest.headers
    .get('set-cookie')
    ?.split(';')
    .at(0);

  if (!authToken) {
    throw new Error('Login failed');
  }

  return {
    getAuth: () => authToken,
    createFile: async () => {
      const createFileRequest = await fetch(
        `${apiUrl}/api/rpc/command/create-file`,
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
                'components/v2',
                'styles/v2',
                'layout/grid',
                'plugins/runtime',
              ],
            },
          }),
        },
      );

      return (await createFileRequest.json()) as FileRpc;
    },
    deleteFile: async (fileId: string) => {
      const deleteFileRequest = await fetch(
        `${apiUrl}/api/rpc/command/delete-file`,
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
