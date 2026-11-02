import type { Router } from 'vue-router';

/** Remove the previous account's runtime routes while preserving public routes. */
export function clearSessionRoutes(router: Router, publicNames: ReadonlySet<string | symbol>) {
  for (const route of router.getRoutes()) {
    if (route.name != null && !publicNames.has(route.name)) router.removeRoute(route.name);
  }
}
