import { describe, it, expect, vi, beforeEach } from 'vitest';
import { openUIApi } from './openUI.api.js';
import { createModal } from '../create-modal.js';
import type { PluginModalElement } from '../modal/plugin-modal.js';

vi.mock('../create-modal.js', () => ({
  createModal: vi.fn(),
}));

describe('openUIApi', () => {
  const mockModal = {} as PluginModalElement;

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(createModal).mockReturnValue(mockModal);
  });

  it('should delegate all arguments to createModal and return the modal', () => {
    const options = { width: 400, height: 300 };

    const result = openUIApi(
      'Test Modal',
      'https://example.com/plugin',
      'light',
      options,
      true,
      false,
      true,
    );

    expect(createModal).toHaveBeenCalledWith(
      'Test Modal',
      'https://example.com/plugin',
      'light',
      options,
      true,
      false,
      true,
    );
    expect(result).toBe(mockModal);
  });

  it('should accept only the required arguments', () => {
    const result = openUIApi(
      'Test Modal',
      'https://example.com/plugin',
      'dark',
    );

    expect(createModal).toHaveBeenCalledWith(
      'Test Modal',
      'https://example.com/plugin',
      'dark',
      undefined,
      undefined,
      undefined,
      undefined,
    );
    expect(result).toBe(mockModal);
  });

  it('should reject an invalid theme', () => {
    // Cast through unknown: the invalid value must reach runtime validation.
    const invalidTheme = 'blue' as unknown as 'light';
    expect(() =>
      openUIApi('Test Modal', 'https://example.com/plugin', invalidTheme),
    ).toThrow();
    expect(createModal).not.toHaveBeenCalled();
  });

  it('should reject non-string titles', () => {
    // Cast through unknown: the invalid value must reach runtime validation.
    const invalidTitle = 42 as unknown as string;
    expect(() =>
      openUIApi(invalidTitle, 'https://example.com/plugin', 'light'),
    ).toThrow();
    expect(createModal).not.toHaveBeenCalled();
  });
});
