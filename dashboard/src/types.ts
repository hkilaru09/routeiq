export type IssueStatus = 'NEW' | 'ROUTED' | 'ACKNOWLEDGED' | 'RESOLVED'

export interface Issue {
  id: string
  tenantId: string
  externalId?: string
  title: string
  description?: string
  category?: string
  attributes: Record<string, string>
  status: IssueStatus
  teamId?: string
  teamSlug?: string
  ruleId?: string
  slaDueAt?: string
  breachedAt?: string
  escalationLevel: number
  sourceCreatedAt?: string
  createdAt: string
  routedAt?: string
  resolvedAt?: string
}

export interface Tenant {
  id: string
  name: string
  defaultSlaMinutes: number
}

export interface Team {
  id: string
  tenantId: string
  slug: string
  name: string
  isDefault: boolean
}

export type Operator = 'EQUALS' | 'CONTAINS' | 'PREFIX' | 'IN' | 'REGEX'

export interface Rule {
  id: string
  tenantId: string
  name: string
  priority: number
  field: string
  operator: Operator
  matchValue: string
  teamId: string
  slaMinutes: number
  enabled: boolean
}

export interface BoardEvent {
  type: 'CREATED' | 'ROUTED' | 'ESCALATION' | 'ACKNOWLEDGED' | 'RESOLVED'
  issue: Issue
}
