/**
 * `GET /health` (SPEC.md §4.7).
 *
 * Returns the standard envelope like everything else — a healthcheck that broke the
 * universal contract would be the one endpoint a client could not parse. Reports `200`
 * when the service itself is healthy even if a dependency is degraded; the body says
 * which.
 */
import { Router } from 'express';
import { successEnvelope } from '../envelope.js';

export interface HealthProbes {
  upstream: () => Promise<boolean>;
  smtp: () => Promise<boolean>;
}

export function healthRouter(probes: HealthProbes): Router {
  const router = Router();

  router.get('/health', async (_req, res) => {
    const [upstream, smtp] = await Promise.all([probes.upstream(), probes.smtp()]);
    res.json(
      successEnvelope({
        status: 'ok',
        upstream: upstream ? 'reachable' : 'unreachable',
        smtp: smtp ? 'reachable' : 'unreachable',
      }),
    );
  });

  return router;
}
