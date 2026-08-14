import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'

import { AppearanceProvider } from '../appearance/AppearanceProvider'
import { AppearanceSettingsDialog } from './AppearanceSettingsDialog'

describe('AppearanceSettingsDialog', () => {
  it('applies a theme selection and exposes all appearance axes', async () => {
    const user = userEvent.setup()
    render(<AppearanceProvider><AppearanceSettingsDialog onClose={vi.fn()} /></AppearanceProvider>)

    const dialog = screen.getByRole('dialog', { name: '外观设置' })
    expect(screen.getByRole('button', { name: '石墨' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: '纸页' })).toBeVisible()
    expect(screen.getByRole('radiogroup', { name: '颜色模式' })).toBeVisible()
    expect(screen.getByRole('radiogroup', { name: '界面字体' })).toBeVisible()
    expect(screen.getByRole('radiogroup', { name: '界面密度' })).toBeVisible()
    expect(screen.getByRole('radiogroup', { name: '圆角' })).toBeVisible()

    await user.click(screen.getByRole('button', { name: '纸页' }))
    expect(document.documentElement).toHaveAttribute('data-theme-preset', 'PAPER')
    expect(dialog).toBeVisible()
  })

  it('closes with Escape', async () => {
    const user = userEvent.setup()
    const onClose = vi.fn()
    render(<AppearanceProvider><AppearanceSettingsDialog onClose={onClose} /></AppearanceProvider>)

    await user.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledTimes(1)
  })
})
