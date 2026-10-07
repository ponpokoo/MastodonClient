// Hybrid delivery entry. Keep the production origin, registrations and FCM bindings on cutover.
import { createWorker } from './worker.mjs';
import { LIMITS } from './store.mjs';

export default createWorker({ deliveryMode: 'hybrid', limits: { ...LIMITS, registrations: 300, pending: 50 } });
