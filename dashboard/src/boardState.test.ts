import { describe, expect, it } from 'vitest'
import { applyEvent, groupByStatus, slaMinutesLeft } from './boardState'
import type { Issue } from './types'

const issue = (id: string, overrides: Partial<Issue> = {}): Issue => ({
  id,
  tenantId: 'nyc311',
  title: `issue ${id}`,
  attributes: {},
  status: 'NEW',
  escalationLevel: 0,
  createdAt: '2026-10-30T00:00:00Z',
  ...overrides,
})

describe('applyEvent', () => {
  it('replaces the previous snapshot and moves it to the front', () => {
    const before = [issue('a'), issue('b')]
    const after = applyEvent(before, { type: 'ROUTED', issue: issue('b', { status: 'ROUTED' }) })
    expect(after.map((i) => [i.id, i.status])).toEqual([
      ['b', 'ROUTED'],
      ['a', 'NEW'],
    ])
  })

  it('ignores a stale event that would move an issue backwards', () => {
    const before = [issue('a', { status: 'RESOLVED' })]
    expect(applyEvent(before, { type: 'ROUTED', issue: issue('a', { status: 'ROUTED' }) })).toBe(before)
  })

  it('accepts an escalation of an already-routed issue', () => {
    const before = [issue('a', { status: 'ROUTED' })]
    const after = applyEvent(before, {
      type: 'ESCALATION',
      issue: issue('a', { status: 'ROUTED', escalationLevel: 1, breachedAt: '2026-10-30T02:00:00Z' }),
    })
    expect(after[0].escalationLevel).toBe(1)
  })

  it('caps the list', () => {
    const many = Array.from({ length: 5 }, (_, n) => issue(String(n)))
    expect(applyEvent(many, { type: 'CREATED', issue: issue('new') }, 3)).toHaveLength(3)
  })
})

describe('groupByStatus', () => {
  it('puts every issue in its column', () => {
    const groups = groupByStatus([issue('a'), issue('b', { status: 'RESOLVED' })])
    expect(groups.NEW).toHaveLength(1)
    expect(groups.RESOLVED).toHaveLength(1)
    expect(groups.ROUTED).toHaveLength(0)
  })
})

describe('slaMinutesLeft', () => {
  it('is negative once the SLA has passed', () => {
    const now = new Date('2026-10-30T01:00:00Z').getTime()
    expect(slaMinutesLeft(issue('a', { slaDueAt: '2026-10-30T00:30:00Z' }), now)).toBe(-30)
    expect(slaMinutesLeft(issue('b'), now)).toBeNull()
  })
})
