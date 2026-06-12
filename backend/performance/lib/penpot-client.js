// Penpot k6 HTTP Client
//
// Shared module that wraps the Penpot backend RPC API using plain JSON.
// The backend supports `application/json` request bodies (kebab-case keys)
// and `application/json` responses (camelCase keys) via Accept header or _fmt=json.
//
// Authentication is cookie-based: login-with-password sets a session cookie,
// and all subsequent requests include it automatically via the k6 cookie jar.

import http from "k6/http";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

/**
 * Creates a new Penpot client instance.
 *
 * @param {string} baseUrl - The base URL of the Penpot backend (e.g., "http://localhost:6060")
 * @returns {object} Client instance with RPC methods
 */
export function createClient(baseUrl) {
  // Per-VU session identifiers — consistent across all requests within one VU iteration
  const sessionId = uuidv4();
  const externalSessionId = uuidv4();

  const defaultHeaders = {
    "Accept": "application/json",
    "x-session-id": sessionId,
    "x-external-session-id": externalSessionId,
    "x-event-origin": "perf-test",
    "x-client": "penpot-perf/1.0",
  };

  // k6 automatically manages cookies per VU when `cookies` are returned by the server.
  // We use the default cookie jar which is per-VU.

  /**
   * Make an RPC call to the Penpot backend.
   *
   * GET requests: params go as query parameters, response is JSON via _fmt=json.
   * POST requests: params go as JSON body, response is JSON via Accept header.
   *
   * @param {string} method - HTTP method ("GET" or "POST")
   * @param {string} command - RPC command name (e.g., "login-with-password")
   * @param {object} params - Parameters for the RPC call
   * @param {object} [opts] - Additional options
   * @param {string} [opts.tag] - k6 metric tag for this request
   * @returns {object} k6 Response object with parsed JSON body
   */
  function rpc(method, command, params = {}, opts = {}) {
    const url = `${baseUrl}/api/main/methods/${command}`;
    const tag = opts.tag || command;

    const tags = {
      rpc_command: tag,
    };

    if (method === "GET") {
      // GET requests: params go as query string, add _fmt=json for JSON response
      const queryParams = { ...params, _fmt: "json" };
      const qs = Object.entries(queryParams)
        .filter(([, v]) => v !== undefined && v !== null)
        .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
        .join("&");

      const fullUrl = qs ? `${url}?${qs}` : url;

      return http.get(fullUrl, {
        headers: defaultHeaders,
        tags,
      });
    } else {
      // POST requests: params go as JSON body
      const headers = {
        ...defaultHeaders,
        "Content-Type": "application/json",
      };

      return http.post(url, JSON.stringify(params), {
        headers,
        tags,
      });
    }
  }

  /**
   * Login with email and password.
   * Returns the profile data on success. The session cookie is stored
   * automatically by k6's cookie jar.
   *
   * @param {string} email
   * @param {string} password
   * @returns {object} Parsed response { status, body }
   */
  function login(email, password) {
    const res = rpc("POST", "login-with-password", {
      email,
      password,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get the current user's profile (requires prior login).
   *
   * @returns {object} Parsed response { status, body }
   */
  function getProfile() {
    const res = rpc("GET", "get-profile");
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get all teams for the current user.
   *
   * @returns {object} Parsed response { status, body }
   */
  function getTeams() {
    const res = rpc("GET", "get-teams");
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Create a new team.
   *
   * @param {string} name - Team name
   * @returns {object} Parsed response { status, body }
   */
  function createTeam(name) {
    const res = rpc("POST", "create-team", { name });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get projects for a team.
   *
   * @param {string} teamId - Team UUID
   * @returns {object} Parsed response { status, body }
   */
  function getProjects(teamId) {
    const res = rpc("GET", "get-projects", { "team-id": teamId });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Create a new project.
   *
   * @param {string} teamId - Team UUID
   * @param {string} name - Project name
   * @returns {object} Parsed response { status, body }
   */
  function createProject(teamId, name) {
    const res = rpc("POST", "create-project", {
      "team-id": teamId,
      name,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Create a new file in a project.
   *
   * @param {string} projectId - Project UUID
   * @param {string} name - File name
   * @returns {object} Parsed response { status, body }
   */
  function createFile(projectId, name) {
    const res = rpc("POST", "create-file", {
      "project-id": projectId,
      name,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get a file by ID.
   *
   * @param {string} fileId - File UUID
   * @returns {object} Parsed response { status, body }
   */
  function getFile(fileId) {
    const res = rpc("GET", "get-file", {
      id: fileId,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get libraries used by a file.
   *
   * @param {string} fileId - File UUID
   * @returns {object} Parsed response { status, body }
   */
  function getFileLibraries(fileId) {
    const res = rpc("GET", "get-file-libraries", {
      "file-id": fileId,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get object thumbnails for a file.
   *
   * @param {string} fileId - File UUID
   * @returns {object} Parsed response { status, body }
   */
  function getFileObjectThumbnails(fileId) {
    const res = rpc("GET", "get-file-object-thumbnails", {
      "file-id": fileId,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get file data for thumbnail generation.
   *
   * @param {string} fileId - File UUID
   * @returns {object} Parsed response { status, body }
   */
  function getFileDataForThumbnail(fileId) {
    const res = rpc("GET", "get-file-data-for-thumbnail", {
      "file-id": fileId,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Update a file with changes.
   *
   * The backend uses optimistic concurrency control via `revn`.
   * If a conflict occurs (status 400 with :revn-conflict), the caller
   * should retry with the latest revn from getFile().
   *
   * @param {string} fileId - File UUID
   * @param {number} revn - Current file revision number
   * @param {number} vern - Current file version number
   * @param {string} sessionId - Client session ID (UUID)
   * @param {Array} changes - Array of change objects
   * @returns {object} Parsed response { status, body }
   */
  function updateFile(fileId, revn, vern, changesSessionId, changes) {
    const params = {
      id: fileId,
      revn: revn,
      vern: vern,
      "session-id": changesSessionId,
      origin: "workspace",
      "created-at": new Date().toISOString(),
      "commit-id": uuidv4(),
      changes: changes,
    };

    // update-file uses POST with id also as query param (per frontend convention)
    const url = `${baseUrl}/api/main/methods/update-file?id=${encodeURIComponent(fileId)}`;
    const headers = {
      ...defaultHeaders,
      "Content-Type": "application/json",
    };

    const res = http.post(url, JSON.stringify(params), {
      headers,
      tags: { rpc_command: "update-file" },
    });

    let body = null;
    try {
      if (res.body && res.body.length > 0) {
        body = res.json();
      }
    } catch (e) {
      // body may not be JSON
    }

    return {
      status: res.status,
      body: body,
      raw: res,
    };
  }

  /**
   * Upload a file media object using direct multipart upload.
   *
   * @param {string} fileId - File UUID
   * @param {Uint8Array} fileBytes - The file content
   * @param {string} fileName - The file name
   * @param {string} mimeType - MIME type (e.g., "image/png")
   * @returns {object} Parsed response { status, body }
   */
  function uploadFileMediaObjectDirect(fileId, fileBytes, fileName, mimeType) {
    const url = `${baseUrl}/api/main/methods/upload-file-media-object`;

    const headers = {
      ...defaultHeaders,
      // No Content-Type — k6 sets it automatically for multipart/form-data
    };

    const formData = {
      "file-id": fileId,
      "is-local": "true",
      name: fileName,
      content: http.file(fileBytes, fileName, mimeType),
    };

    const res = http.post(url, formData, {
      headers,
      tags: { rpc_command: "upload-file-media-object" },
    });

    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  // -----------------------------------------------------------------------
  // Chunked upload
  // -----------------------------------------------------------------------

  /**
   * Create an upload session for chunked uploads.
   *
   * @param {number} totalChunks - Number of chunks
   * @returns {object} { status, sessionId }
   */
  function createUploadSession(totalChunks) {
    const res = rpc("POST", "create-upload-session", {
      "total-chunks": totalChunks,
    });
    const body = res.status === 200 ? res.json() : null;
    return {
      status: res.status,
      sessionId: body ? body.sessionId : null,
      raw: res,
    };
  }

  /**
   * Upload a single chunk within an upload session.
   *
   * @param {string} sessionId - Upload session UUID
   * @param {number} index - Chunk index (0-based)
   * @param {Uint8Array} chunkBytes - The chunk content
   * @param {string} fileName - Original file name
   * @param {string} mimeType - MIME type
   * @returns {object} Parsed response { status, body }
   */
  function uploadChunk(sessionId, index, chunkBytes, fileName, mimeType) {
    const url = `${baseUrl}/api/main/methods/upload-chunk`;

    const headers = {
      ...defaultHeaders,
    };

    const formData = {
      "session-id": sessionId,
      index: String(index),
      content: http.file(chunkBytes, fileName, mimeType),
    };

    const res = http.post(url, formData, {
      headers,
      tags: { rpc_command: "upload-chunk" },
    });

    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Assemble all uploaded chunks into a final media object.
   *
   * @param {string} sessionId - Upload session UUID
   * @param {string} fileId - File UUID
   * @param {string} name - Media object name
   * @param {boolean} isLocal - Whether the object is local to the file
   * @param {string} mimeType - MIME type (e.g., "image/png")
   * @returns {object} Parsed response { status, body }
   */
  function assembleFileMediaObject(sessionId, fileId, name, isLocal, mimeType) {
    const res = rpc("POST", "assemble-file-media-object", {
      "session-id": sessionId,
      "file-id": fileId,
      name: name,
      "is-local": isLocal,
      mtype: mimeType,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  // -----------------------------------------------------------------------
  // Smart upload — picks direct or chunked based on file size
  // -----------------------------------------------------------------------

  // Chunk size threshold: files larger than this use chunked upload.
  // The actual chunk size is irrelevant to the backend; this controls
  // which upload path is exercised.
  const CHUNK_SIZE = 50 * 1024; // 50 KB

  /**
   * Upload a file media object, automatically selecting direct or chunked
   * upload based on file size.
   *
   * Files <= CHUNK_SIZE use direct multipart upload.
   * Files > CHUNK_SIZE use chunked upload (create-upload-session →
   * upload-chunk × N → assemble-file-media-object).
   *
   * @param {string} fileId - File UUID
   * @param {Uint8Array} fileBytes - The file content
   * @param {string} fileName - The file name
   * @param {string} mimeType - MIME type (e.g., "image/png")
   * @returns {object} Parsed response { status, body }
   */
  function uploadFileMediaObject(fileId, fileBytes, fileName, mimeType) {
    if (fileBytes.byteLength <= CHUNK_SIZE) {
      return uploadFileMediaObjectDirect(fileId, fileBytes, fileName, mimeType);
    }

    // Chunked upload path
    const totalChunks = Math.ceil(fileBytes.byteLength / CHUNK_SIZE);

    const sessionRes = createUploadSession(totalChunks);
    if (sessionRes.status !== 200) {
      return { status: sessionRes.status, body: null, raw: sessionRes.raw };
    }
    const uploadSessionId = sessionRes.sessionId;

    for (let i = 0; i < totalChunks; i++) {
      const start = i * CHUNK_SIZE;
      const end = Math.min(start + CHUNK_SIZE, fileBytes.byteLength);
      const chunk = fileBytes.slice(start, end);

      const chunkRes = uploadChunk(uploadSessionId, i, chunk, fileName, mimeType);
      if (chunkRes.status !== 200) {
        return { status: chunkRes.status, body: null, raw: chunkRes.raw };
      }
    }

    return assembleFileMediaObject(uploadSessionId, fileId, fileName, true, mimeType);
  }

  /**
   * Delete a file.
   *
   * @param {string} fileId - File UUID
   * @returns {object} Parsed response { status }
   */
  function deleteFile(fileId) {
    const res = rpc("POST", "delete-file", { id: fileId });
    return {
      status: res.status,
      raw: res,
    };
  }

  /**
   * Delete a project.
   *
   * @param {string} projectId - Project UUID
   * @returns {object} Parsed response { status }
   */
  function deleteProject(projectId) {
    const res = rpc("POST", "delete-project", { id: projectId });
    return {
      status: res.status,
      raw: res,
    };
  }

  /**
   * Delete a team.
   *
   * @param {string} teamId - Team UUID
   * @returns {object} Parsed response { status }
   */
  function deleteTeam(teamId) {
    const res = rpc("POST", "delete-team", { id: teamId });
    return {
      status: res.status,
      raw: res,
    };
  }

  /**
   * Invite members to a team by email.
   *
   * @param {string} teamId - Team UUID
   * @param {string[]} emails - Array of email addresses
   * @param {string} role - Role for the invited members (e.g. "editor")
   * @returns {object} Parsed response { status, body }
   */
  function inviteTeamMembers(teamId, emails, role) {
    const res = rpc("POST", "create-team-invitations", {
      "team-id": teamId,
      emails: emails,
      role: role,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Get an invitation token for a specific email.
   *
   * @param {string} teamId - Team UUID
   * @param {string} email - Invited email address
   * @returns {object} Parsed response { status, body }
   */
  function getTeamInvitationToken(teamId, email) {
    const res = rpc("GET", "get-team-invitation-token", {
      "team-id": teamId,
      email: email,
    });
    return {
      status: res.status,
      body: res.status === 200 ? res.json() : null,
      raw: res,
    };
  }

  /**
   * Logout the current user.
   *
   * @param {string} profileId - Profile UUID
   * @returns {object} Parsed response { status }
   */
  function logout(profileId) {
    const res = rpc("POST", "logout", { "profile-id": profileId });
    return {
      status: res.status,
      raw: res,
    };
  }

  // Return the client interface
  return {
    sessionId,
    externalSessionId,
    rpc,
    login,
    getProfile,
    getTeams,
    createTeam,
    getProjects,
    createProject,
    createFile,
    getFile,
    getFileLibraries,
    getFileObjectThumbnails,
    getFileDataForThumbnail,
    updateFile,
    uploadFileMediaObject,
    uploadFileMediaObjectDirect,
    createUploadSession,
    uploadChunk,
    assembleFileMediaObject,
    deleteFile,
    deleteProject,
    deleteTeam,
    inviteTeamMembers,
    getTeamInvitationToken,
    logout,
  };
}
