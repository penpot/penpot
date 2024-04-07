import { expect, test } from 'vitest'

test('use jsdom in this test file', () => {
  const element = document.createElement('div')
  expect(element).not.toBeNull()
})

test('adds 1 + 2 to equal 3', () => {
  expect(1 +2).toBe(3)
});
