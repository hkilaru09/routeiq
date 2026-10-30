import type { Issue, Rule, Team, Tenant } from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `${response.status} ${response.statusText}`)
  }
  return response.status === 204 ? (undefined as T) : response.json()
}

export type RuleInput = Omit<Rule, 'id' | 'tenantId'>

export const api = {
  tenants: () => request<Tenant[]>('/api/tenants'),
  teams: (tenant: string) => request<Team[]>(`/api/tenants/${tenant}/teams`),
  issues: (tenant: string, limit = 200) => request<Issue[]>(`/api/tenants/${tenant}/issues?limit=${limit}`),
  stats: (tenant: string) => request<Record<string, number>>(`/api/tenants/${tenant}/issues/stats`),
  acknowledge: (tenant: string, id: string) =>
    request<Issue>(`/api/tenants/${tenant}/issues/${id}/acknowledge`, { method: 'POST' }),
  resolve: (tenant: string, id: string) =>
    request<Issue>(`/api/tenants/${tenant}/issues/${id}/resolve`, { method: 'POST' }),
  rules: (tenant: string) => request<Rule[]>(`/api/tenants/${tenant}/rules`),
  createRule: (tenant: string, rule: RuleInput) =>
    request<Rule>(`/api/tenants/${tenant}/rules`, { method: 'POST', body: JSON.stringify(rule) }),
  updateRule: (tenant: string, id: string, rule: RuleInput) =>
    request<Rule>(`/api/tenants/${tenant}/rules/${id}`, { method: 'PUT', body: JSON.stringify(rule) }),
  deleteRule: (tenant: string, id: string) =>
    request<void>(`/api/tenants/${tenant}/rules/${id}`, { method: 'DELETE' }),
  dryRun: (tenant: string, issue: { title: string; category: string; attributes: Record<string, string> }) =>
    request<{ rule?: Rule; team?: Team; slaMinutes: number }>(`/api/tenants/${tenant}/rules/dry-run`, {
      method: 'POST',
      body: JSON.stringify(issue),
    }),
}

export function boardSocketUrl(tenant: string): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws/board?tenant=${encodeURIComponent(tenant)}`
}
