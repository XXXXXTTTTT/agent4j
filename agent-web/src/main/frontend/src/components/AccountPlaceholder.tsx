import { UserRound } from 'lucide-react'

import type { Actor } from '../api/contracts'

interface AccountPlaceholderProps {
  identity: Actor | null
}

/** 展示服务端身份，并为后续账户菜单保留唯一挂载位置。 */
export function AccountPlaceholder({ identity }: AccountPlaceholderProps) {
  return (
    <div className="account-placeholder">
      <UserRound aria-hidden="true" size={15} />
      <div className="account-placeholder-copy">
        <span>本地身份</span>
        <strong>{identity?.displayName ?? '加载身份中'}</strong>
        {identity === null ? null : <code>{identity.userId}</code>}
      </div>
      <span data-testid="account-menu-mount" aria-hidden="true" />
    </div>
  )
}
