import { useEffect, useState } from 'react'
import { api } from './api'
import Board from './Board'
import Rules from './Rules'
import type { Tenant } from './types'

type Tab = 'board' | 'rules'

export default function App() {
  const [tenants, setTenants] = useState<Tenant[]>([])
  const [tenant, setTenant] = useState<string>('')
  const [tab, setTab] = useState<Tab>('board')
  const [error, setError] = useState<string>()

  useEffect(() => {
    api
      .tenants()
      .then((list) => {
        setTenants(list)
        setTenant((current) => current || list[0]?.id || '')
      })
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">RouteIQ</div>
        <nav className="tabs" aria-label="Views">
          <button className={tab === 'board' ? 'active' : ''} onClick={() => setTab('board')}>
            Live board
          </button>
          <button className={tab === 'rules' ? 'active' : ''} onClick={() => setTab('rules')}>
            Routing rules
          </button>
        </nav>
        <label className="tenant">
          Tenant
          <select value={tenant} onChange={(e) => setTenant(e.target.value)}>
            {tenants.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
      </header>
      {error && <div className="error">Cannot reach the API: {error}</div>}
      <main>{tenant && (tab === 'board' ? <Board tenant={tenant} /> : <Rules tenant={tenant} />)}</main>
    </div>
  )
}
