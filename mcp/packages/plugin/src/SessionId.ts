/** Short routing identifier for a Penpot session. */
export class SessionId {
    private static readonly ALPHABET = "abcdefghijklmnopqrstuvwxyz234567";

    public static async forFile(tabId: string, fileId: string): Promise<string> {
        const input = new TextEncoder().encode(JSON.stringify([tabId, fileId]));
        const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", input));
        return SessionId.encode(digest);
    }

    protected static encode(bytes: Uint8Array): string {
        let result = "";
        for (let bit = 0; bit < 50; bit += 5) {
            const byte = Math.floor(bit / 8);
            const offset = bit % 8;
            const value = ((bytes[byte] << 8) | bytes[byte + 1]) >> (11 - offset);
            result += SessionId.ALPHABET[value & 31];
        }
        return result;
    }
}
