import type { BoardEvent, Issue, IssueStatus } from './types'

export const COLUMNS: IssueStatus[] = ['NEW', 'ROUTED', 'ACKNOWLEDGED', 'RESOLVED']

/** Keeps the newest `limit` issues, replacing any older snapshot of the same issue. */
export function applyEvent(issues: Issue[], event: BoardEvent, limit = 500): Issue[] {
  const incoming = event.issue
  const existing = issues.find((i) => i.id === incoming.id)
  // Events can arrive out of order across topics; never move an issue backwards.
  if (existing && rank(existing) > rank(incoming)) {
    return issues
  }
  const rest = issues.filter((i) => i.id !== incoming.id)
  return [incoming, ...rest].slice(0, limit)
}

function rank(issue: Issue): number {
  return COLUMNS.indexOf(issue.status) * 10 + issue.escalationLevel
}

export function groupByStatus(issues: Issue[]): Record<IssueStatus, Issue[]> {
  const groups = Object.fromEntries(COLUMNS.map((c) => [c, [] as Issue[]])) as Record<IssueStatus, Issue[]>
  for (const issue of issues) {
    groups[issue.status].push(issue)
  }
  return groups
}

/** Minutes until the SLA is due (negative once breached), or null if there is no SLA yet. */
export function slaMinutesLeft(issue: Issue, now: number = Date.now()): number | null {
  if (!issue.slaDueAt) return null
  return Math.round((new Date(issue.slaDueAt).getTime() - now) / 60000)
}
