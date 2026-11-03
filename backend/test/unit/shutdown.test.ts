import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  installShutdownHandlers,
  type ClosableServer,
} from '../../src/http/shutdown.js';

/** A stand-in for `http.Server` whose close can be resolved or failed on demand. */
function fakeServer() {
  let finish: (error?: Error) => void = () => {};
  const server = {
    close: vi.fn((callback: (error?: Error) => void) => {
      finish = callback;
      return server;
    }),
    closeIdleConnections: vi.fn(),
  };
  return { server: server as unknown as ClosableServer, spy: server, finish: () => finish };
}

const tick = () => new Promise((resolve) => setImmediate(resolve));

let dispose: (() => void) | undefined;

afterEach(() => {
  dispose?.();
  dispose = undefined;
});

describe('installShutdownHandlers', () => {
  it('closes the server when a stop signal arrives', async () => {
    const { server, spy } = fakeServer();
    const onExit = vi.fn();
    dispose = installShutdownHandlers(server, { graceMs: 1000, signals: ['SIGUSR2'], onExit });

    process.emit('SIGUSR2');
    await tick();

    expect(spy.close).toHaveBeenCalledTimes(1);
  });

  it('releases idle keep-alive sockets, which would otherwise hold it open', async () => {
    const { server, spy } = fakeServer();
    dispose = installShutdownHandlers(server, {
      graceMs: 1000,
      signals: ['SIGUSR2'],
      onExit: vi.fn(),
    });

    process.emit('SIGUSR2');
    await tick();

    expect(spy.closeIdleConnections).toHaveBeenCalledTimes(1);
  });

  it('exits zero once in-flight requests have finished', async () => {
    const { server, finish } = fakeServer();
    const onExit = vi.fn();
    dispose = installShutdownHandlers(server, { graceMs: 1000, signals: ['SIGUSR2'], onExit });

    process.emit('SIGUSR2');
    await tick();
    expect(onExit).not.toHaveBeenCalled();

    finish()();
    expect(onExit).toHaveBeenCalledWith(0);
  });

  it('exits non-zero when the grace period expires with work still in flight', async () => {
    const { server } = fakeServer();
    const onExit = vi.fn();
    dispose = installShutdownHandlers(server, { graceMs: 10, signals: ['SIGUSR2'], onExit });

    process.emit('SIGUSR2');
    // Never resolve the close callback: this is the hung-request case.
    await new Promise((resolve) => setTimeout(resolve, 40));

    // Non-zero says plainly that work was abandoned, rather than reporting a clean stop.
    expect(onExit).toHaveBeenCalledWith(1);
  });

  it('exits non-zero when the server fails to close', async () => {
    const { server, finish } = fakeServer();
    const onExit = vi.fn();
    dispose = installShutdownHandlers(server, { graceMs: 1000, signals: ['SIGUSR2'], onExit });

    process.emit('SIGUSR2');
    await tick();
    finish()(new Error('still listening'));

    expect(onExit).toHaveBeenCalledWith(1);
  });

  it('does not start a second shutdown on an impatient second signal', async () => {
    const { server, spy } = fakeServer();
    const onExit = vi.fn();
    dispose = installShutdownHandlers(server, { graceMs: 1000, signals: ['SIGUSR2'], onExit });

    process.emit('SIGUSR2');
    process.emit('SIGUSR2');
    process.emit('SIGUSR2');
    await tick();

    expect(spy.close).toHaveBeenCalledTimes(1);
  });

  it('does not exit twice when the grace timer and a late close both fire', async () => {
    const { server, finish } = fakeServer();
    const onExit = vi.fn();
    dispose = installShutdownHandlers(server, { graceMs: 10, signals: ['SIGUSR2'], onExit });

    process.emit('SIGUSR2');
    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(onExit).toHaveBeenCalledWith(1);

    // A close that lands after the forced exit must not report success over it.
    finish()();
    expect(onExit).toHaveBeenCalledTimes(1);
  });

  it('listens on SIGTERM and SIGINT by default', () => {
    const { server } = fakeServer();
    const before = {
      term: process.listenerCount('SIGTERM'),
      int: process.listenerCount('SIGINT'),
    };
    dispose = installShutdownHandlers(server, { graceMs: 10, onExit: vi.fn() });

    expect(process.listenerCount('SIGTERM')).toBe(before.term + 1);
    expect(process.listenerCount('SIGINT')).toBe(before.int + 1);
  });

  it('removes its handlers when disposed, leaving no listener behind', () => {
    const { server } = fakeServer();
    const before = process.listenerCount('SIGTERM');
    const off = installShutdownHandlers(server, { graceMs: 10, onExit: vi.fn() });
    off();
    expect(process.listenerCount('SIGTERM')).toBe(before);
  });
});
