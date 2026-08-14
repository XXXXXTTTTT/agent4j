import { Check, MonitorCog, RotateCcw, X } from 'lucide-react'
import { useEffect } from 'react'

import { type ColorMode, type ThemePreset, type UiDensity, type UiFont, type UiRadius } from '../appearance/appearancePreferences'
import { useAppearance } from '../appearance/AppearanceProvider'

interface Props { onClose(): void }

const PRESETS: Array<{ value: ThemePreset; label: string }> = [
  { value: 'GRAPHITE', label: '石墨' },
  { value: 'PAPER', label: '纸页' },
  { value: 'PINE', label: '松林' },
  { value: 'SIGNAL', label: '信号' },
  { value: 'HARBOR', label: '海港' },
]

const MODES: Array<{ value: ColorMode; label: string }> = [{ value: 'SYSTEM', label: '跟随系统' }, { value: 'LIGHT', label: '浅色' }, { value: 'DARK', label: '深色' }]
const FONTS: Array<{ value: UiFont; label: string }> = [{ value: 'SANS', label: '无衬线' }, { value: 'SERIF', label: '衬线' }, { value: 'MONO', label: '等宽' }]
const DENSITIES: Array<{ value: UiDensity; label: string }> = [{ value: 'COMPACT', label: '紧凑' }, { value: 'STANDARD', label: '标准' }, { value: 'COMFORTABLE', label: '舒展' }]
const RADII: Array<{ value: UiRadius; label: string }> = [{ value: 'SHARP', label: '直角' }, { value: 'SOFT', label: '柔和' }, { value: 'ROUND', label: '圆润' }]

function OptionGroup<T extends string>({ label, options, value, onChange }: { label: string; options: Array<{ value: T; label: string }>; value: T; onChange(value: T): void }) {
  return <section className="appearance-option-group"><h3>{label}</h3><div className="appearance-option-list" role="radiogroup" aria-label={label}>{options.map((option) => <button key={option.value} type="button" role="radio" aria-checked={option.value === value} onClick={() => onChange(option.value)}>{option.label}</button>)}</div></section>
}

export function AppearanceSettingsDialog({ onClose }: Props) {
  const { preferences, updatePreferences, resetPreferences } = useAppearance()
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => { if (event.key === 'Escape') onClose() }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [onClose])
  return <div className="appearance-backdrop" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose() }}>
    <aside className="appearance-drawer" role="dialog" aria-modal="true" aria-label="外观设置">
      <header className="appearance-drawer-header"><div><span className="appearance-kicker"><MonitorCog aria-hidden="true" size={14} />WORKBENCH</span><h2>外观设置</h2><p>调整工作台的颜色、排版与信息密度。</p></div><button className="icon-button" type="button" aria-label="关闭外观设置" title="关闭" onClick={onClose}><X aria-hidden="true" size={17} /></button></header>
      <div className="appearance-drawer-content">
        <section className="appearance-option-group"><h3>主题预设</h3><div className="appearance-preset-grid">{PRESETS.map((preset) => <button key={preset.value} type="button" className={`appearance-preset appearance-preset-${preset.value.toLowerCase()}`} aria-pressed={preferences.themePreset === preset.value} onClick={() => updatePreferences({ themePreset: preset.value })}><span className="appearance-preset-swatch" aria-hidden="true"><i /><i /><i /></span><span>{preset.label}</span>{preferences.themePreset === preset.value ? <Check aria-hidden="true" size={14} /> : null}</button>)}</div></section>
        <OptionGroup label="颜色模式" options={MODES} value={preferences.colorMode} onChange={(colorMode) => updatePreferences({ colorMode })} />
        <OptionGroup label="界面字体" options={FONTS} value={preferences.font} onChange={(font) => updatePreferences({ font })} />
        <OptionGroup label="界面密度" options={DENSITIES} value={preferences.density} onChange={(density) => updatePreferences({ density })} />
        <OptionGroup label="圆角" options={RADII} value={preferences.radius} onChange={(radius) => updatePreferences({ radius })} />
      </div>
      <footer className="appearance-drawer-footer"><button type="button" className="secondary-command" onClick={resetPreferences}><RotateCcw aria-hidden="true" size={15} />恢复默认外观</button></footer>
    </aside>
  </div>
}
