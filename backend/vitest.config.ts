import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['test/**/*.test.ts'],
    environment: 'node',
    restoreMocks: true,
    env: {
      NODE_ENV: 'test',
      // The suite deliberately drives every failure path, and each one logs its raw
      // cause by design. Silenced so a real failure is readable in the output.
      LOG_LEVEL: 'silent',
    },
  },
});
