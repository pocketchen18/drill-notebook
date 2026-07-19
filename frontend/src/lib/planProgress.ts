import { post } from './api';
import type { PlanResourceType } from './types';

export interface CompletePlanResourcesInput {
  resourceType: PlanResourceType;
  resourceIds: number[];
  planDate?: string;
  groupId?: number;
}

/**
 * Mark matching todo plan items as done by resource.
 * Fire-and-forget safe: returns updated count, never throws to caller if you catch.
 */
export async function completePlanResources(
  input: CompletePlanResourcesInput
): Promise<number> {
  const resourceIds = [...new Set(input.resourceIds.filter((id) => Number.isFinite(id) && id > 0))];
  if (!resourceIds.length) return 0;
  const body: Record<string, unknown> = {
    resourceType: input.resourceType,
    resourceIds
  };
  if (input.planDate) body.planDate = input.planDate;
  if (input.groupId != null && input.groupId > 0) body.groupId = input.groupId;
  const result = await post<{ updated: number }>('/api/study-plans/items/complete-by-resources', body);
  return Number(result?.updated ?? 0);
}

/** Read plan scope from calendar deep-link query params. */
export function planScopeFromSearch(searchParams: URLSearchParams): {
  planDate?: string;
  planGroupId?: number;
  planItemId?: number;
} {
  const planDate = searchParams.get('planDate') || undefined;
  const planGroupId = Number(searchParams.get('planGroupId')) || undefined;
  const planItemId = Number(searchParams.get('planItemId')) || undefined;
  return { planDate, planGroupId, planItemId };
}
