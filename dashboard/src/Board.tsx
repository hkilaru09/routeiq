import { useCallback, useEffect, useRef, useState } from 'react'
import { api, boardSocketUrl } from './api'
import { applyEvent, COLUMNS, groupByStatus, slaMinutesLeft } from './boardState'
import type { BoardEvent, Issue } from './types'

const LABELS: Record<string, string> = {
  NEW: 'New',
  ROUTED: 'Routed',
  ACKNOWLEDGED: 'Acknowledged',
  RESOLVED: 'Resolved',
}

export default function Board({ tenant }: { tenant: string }) {
  const [issues, setIssues] = useState<Issue[]>([])
  const [stats, setStats] = useState<Record<string, number>>({})
  const [connected, setConnected] = useState(false)
  const [rate, setRate] = useState(0)
  const [now, setNow] = useState(Date.now())
  const eventCount = useRef(0)

  const refreshStats = useCallback(() => {
    api.stats(tenant).then(setStats).catch(() => undefined)
  }, [tenant])

  useEffect(() => {
    setIssues([])
    api.issues(tenant).then(setIssues).catch(() => undefined)
    refreshStats()

    let socket: WebSocket | undefined
    let retry: ReturnType<typeof setTimeout> | undefined
    let closed = false
    const connect = () => {
      socket = new WebSocket(boardSocketUrl(tenant))
      socket.onopen = () => setConnected(true)
      socket.onclose = () => {
        // A socket closed by tenant switch or unmount must not touch the new socket's state.
        if (closed) return
        setConnected(false)
        retry = setTimeout(connect, 2000)
      }
      socket.onmessage = (message) => {
        const event = JSON.parse(message.data) as BoardEvent
        eventCount.current += 1
        setIssues((current) => applyEvent(current, event))
      }
    }
    connect()

    const ticker = setInterval(() => {
      setRate(eventCount.current)
      eventCount.current = 0
      setNow(Date.now())
    }, 1000)
    const statsTimer = setInterval(refreshStats, 5000)
    return () => {
      closed = true
      clearTimeout(retry)
      clearInterval(ticker)
      clearInterval(statsTimer)
      socket?.close()
    }
  }, [tenant, refreshStats])

  const act = async (action: 'acknowledge' | 'resolve', issue: Issue) => {
    const updated = await api[action](tenant, issue.id)
    setIssues((current) => applyEvent(current, { type: 'RESOLVED', issue: updated }))
  }

  const groups = groupByStatus(issues)

  return (
    <section>
      <div className="statsbar">
        <span className={`dot ${connected ? 'live' : ''}`} aria-hidden /> {connected ? 'Live' : 'Reconnecting'}
        <span className="stat">{rate} events/s</span>
        {['NEW', 'ROUTED', 'ACKNOWLEDGED', 'RESOLVED', 'BREACHED'].map((key) => (
          <span key={key} className={`stat ${key === 'BREACHED' ? 'warn' : ''}`}>
            {key.toLowerCase()}: <strong>{stats[key] ?? 0}</strong>
          </span>
        ))}
      </div>
      <div className="board">
        {COLUMNS.map((column) => (
          <div className="column" key={column}>
            <h2>
              {LABELS[column]} <span className="count">{groups[column].length}</span>
            </h2>
            <ul>
              {groups[column].slice(0, 60).map((issue) => (
                <IssueCard key={issue.id} issue={issue} now={now} onAct={act} />
              ))}
            </ul>
          </div>
        ))}
      </div>
    </section>
  )
}

function IssueCard({
  issue,
  now,
  onAct,
}: {
  issue: Issue
  now: number
  onAct: (action: 'acknowledge' | 'resolve', issue: Issue) => void
}) {
  const left = slaMinutesLeft(issue, now)
  const open = issue.status === 'ROUTED' || issue.status === 'ACKNOWLEDGED'
  return (
    <li className={`card ${issue.breachedAt ? 'breached' : ''}`}>
      <div className="card-title">{issue.title}</div>
      <div className="meta">
        {issue.category && <span>{issue.category}</span>}
        {issue.teamSlug && <span className="team">→ {issue.teamSlug}</span>}
      </div>
      {open && left !== null && (
        <div className={`sla ${left < 0 ? 'late' : ''}`}>
          {issue.breachedAt ? `SLA breached · escalation ${issue.escalationLevel}` : `SLA in ${formatMinutes(left)}`}
        </div>
      )}
      {open && (
        <div className="actions">
          {issue.status === 'ROUTED' && <button onClick={() => onAct('acknowledge', issue)}>Acknowledge</button>}
          <button onClick={() => onAct('resolve', issue)}>Resolve</button>
        </div>
      )}
    </li>
  )
}

function formatMinutes(minutes: number): string {
  if (minutes < 60) return `${minutes}m`
  const hours = Math.floor(minutes / 60)
  return hours < 48 ? `${hours}h ${minutes % 60}m` : `${Math.floor(hours / 24)}d`
}
