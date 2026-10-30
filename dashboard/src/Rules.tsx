import { useEffect, useState } from 'react'
import { api, type RuleInput } from './api'
import type { Operator, Rule, Team } from './types'

const OPERATORS: Operator[] = ['EQUALS', 'CONTAINS', 'PREFIX', 'IN', 'REGEX']

const blank = (teams: Team[]): RuleInput => ({
  name: '',
  priority: 100,
  field: 'category',
  operator: 'CONTAINS',
  matchValue: '',
  teamId: teams[0]?.id ?? '',
  slaMinutes: 240,
  enabled: true,
})

export default function Rules({ tenant }: { tenant: string }) {
  const [rules, setRules] = useState<Rule[]>([])
  const [teams, setTeams] = useState<Team[]>([])
  const [editing, setEditing] = useState<{ id?: string; rule: RuleInput }>()
  const [error, setError] = useState<string>()

  const load = () => {
    api.rules(tenant).then(setRules).catch((e: Error) => setError(e.message))
  }

  useEffect(() => {
    setEditing(undefined)
    load()
    api.teams(tenant).then(setTeams).catch(() => undefined)
  }, [tenant])

  const teamName = (id: string) => teams.find((t) => t.id === id)?.name ?? id

  const save = async () => {
    if (!editing) return
    setError(undefined)
    try {
      if (editing.id) await api.updateRule(tenant, editing.id, editing.rule)
      else await api.createRule(tenant, editing.rule)
      setEditing(undefined)
      load()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const remove = async (rule: Rule) => {
    if (!confirm(`Delete rule "${rule.name}"?`)) return
    await api.deleteRule(tenant, rule.id).catch((e: Error) => setError(e.message))
    load()
  }

  return (
    <section className="rules">
      <div className="rules-head">
        <p>
          Rules run in ascending priority; the first match routes the issue. Unmatched issues go to the tenant's
          default team. Changes take effect on the next issue (the Redis cache is evicted on save).
        </p>
        <button className="primary" onClick={() => setEditing({ rule: blank(teams) })}>
          New rule
        </button>
      </div>
      {error && <div className="error">{error}</div>}
      {editing && (
        <RuleForm
          value={editing.rule}
          teams={teams}
          onChange={(rule) => setEditing({ ...editing, rule })}
          onSave={save}
          onCancel={() => setEditing(undefined)}
        />
      )}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Priority</th>
              <th>Name</th>
              <th>Condition</th>
              <th>Team</th>
              <th>SLA</th>
              <th>Enabled</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {rules.map((rule) => (
              <tr key={rule.id} className={rule.enabled ? '' : 'disabled'}>
                <td>{rule.priority}</td>
                <td>{rule.name}</td>
                <td>
                  <code>
                    {rule.field} {rule.operator} “{rule.matchValue}”
                  </code>
                </td>
                <td>{teamName(rule.teamId)}</td>
                <td>{rule.slaMinutes}m</td>
                <td>{rule.enabled ? 'yes' : 'no'}</td>
                <td className="row-actions">
                  <button
                    onClick={() =>
                      setEditing({
                        id: rule.id,
                        rule: {
                          name: rule.name,
                          priority: rule.priority,
                          field: rule.field,
                          operator: rule.operator,
                          matchValue: rule.matchValue,
                          teamId: rule.teamId,
                          slaMinutes: rule.slaMinutes,
                          enabled: rule.enabled,
                        },
                      })
                    }
                  >
                    Edit
                  </button>
                  <button onClick={() => remove(rule)}>Delete</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <DryRun tenant={tenant} />
    </section>
  )
}

function RuleForm({
  value,
  teams,
  onChange,
  onSave,
  onCancel,
}: {
  value: RuleInput
  teams: Team[]
  onChange: (rule: RuleInput) => void
  onSave: () => void
  onCancel: () => void
}) {
  const set = <K extends keyof RuleInput>(key: K, v: RuleInput[K]) => onChange({ ...value, [key]: v })
  return (
    <form
      className="rule-form"
      onSubmit={(e) => {
        e.preventDefault()
        onSave()
      }}
    >
      <label>
        Name
        <input required value={value.name} onChange={(e) => set('name', e.target.value)} />
      </label>
      <label>
        Priority
        <input type="number" min={0} value={value.priority} onChange={(e) => set('priority', Number(e.target.value))} />
      </label>
      <label>
        Field
        <input
          required
          value={value.field}
          list="fields"
          onChange={(e) => set('field', e.target.value)}
          placeholder="category or attr.agency"
        />
        <datalist id="fields">
          {['category', 'title', 'description', 'attr.agency', 'attr.borough', 'attr.state', 'attr.company'].map((f) => (
            <option key={f} value={f} />
          ))}
        </datalist>
      </label>
      <label>
        Operator
        <select value={value.operator} onChange={(e) => set('operator', e.target.value as Operator)}>
          {OPERATORS.map((op) => (
            <option key={op}>{op}</option>
          ))}
        </select>
      </label>
      <label className="wide">
        Value
        <input required value={value.matchValue} onChange={(e) => set('matchValue', e.target.value)} />
      </label>
      <label>
        Team
        <select value={value.teamId} onChange={(e) => set('teamId', e.target.value)}>
          {teams.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        SLA (minutes)
        <input type="number" min={1} value={value.slaMinutes} onChange={(e) => set('slaMinutes', Number(e.target.value))} />
      </label>
      <label className="check">
        <input type="checkbox" checked={value.enabled} onChange={(e) => set('enabled', e.target.checked)} />
        Enabled
      </label>
      <div className="form-actions">
        <button type="submit" className="primary">
          Save
        </button>
        <button type="button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  )
}

function DryRun({ tenant }: { tenant: string }) {
  const [title, setTitle] = useState('Loud music at 2am')
  const [category, setCategory] = useState('Noise - Residential')
  const [attrs, setAttrs] = useState('agency=NYPD')
  const [result, setResult] = useState<string>()

  const run = async () => {
    const attributes = Object.fromEntries(
      attrs
        .split(',')
        .map((pair) => pair.split('=').map((s) => s.trim()))
        .filter(([k, v]) => k && v),
    )
    try {
      const r = await api.dryRun(tenant, { title, category, attributes })
      setResult(`${r.rule ? `Rule “${r.rule.name}”` : 'No rule matched (default team)'} → ${r.team?.name ?? '?'}, SLA ${r.slaMinutes}m`)
    } catch (e) {
      setResult((e as Error).message)
    }
  }

  return (
    <div className="dry-run">
      <h3>Try the rules</h3>
      <div className="dry-run-fields">
        <input value={title} onChange={(e) => setTitle(e.target.value)} aria-label="Title" placeholder="Title" />
        <input value={category} onChange={(e) => setCategory(e.target.value)} aria-label="Category" placeholder="Category" />
        <input value={attrs} onChange={(e) => setAttrs(e.target.value)} aria-label="Attributes" placeholder="key=value, key=value" />
        <button onClick={run}>Route it</button>
      </div>
      {result && <p className="dry-run-result">{result}</p>}
    </div>
  )
}
