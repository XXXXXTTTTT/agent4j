import { Check, ChevronDown } from 'lucide-react'
import { type KeyboardEvent, useEffect, useId, useRef, useState } from 'react'

export interface ThemedSelectOption {
  value: string
  label: string
}

interface ThemedSelectProps {
  id?: string
  label: string
  value: string
  options: readonly ThemedSelectOption[]
  emptyLabel: string
  disabled?: boolean
  onChange: (value: string) => void
}

/** 使用应用主题渲染可键盘操作的单选列表，避免原生菜单脱离主题。 */
export function ThemedSelect({ id, label, value, options, emptyLabel, disabled = false, onChange }: ThemedSelectProps) {
  const generatedId = useId()
  const controlId = id ?? `themed-select-${generatedId}`
  const listboxId = `${controlId}-listbox`
  const wrapperRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const optionRefs = useRef<Array<HTMLButtonElement | null>>([])
  const [open, setOpen] = useState(false)
  const selectedIndex = options.findIndex((option) => option.value === value)
  const [activeIndex, setActiveIndex] = useState(Math.max(selectedIndex, 0))
  const selectedOption = selectedIndex < 0 ? null : options[selectedIndex]
  const unavailable = disabled || options.length === 0

  useEffect(() => {
    if (!open) return
    optionRefs.current[activeIndex]?.scrollIntoView?.({ block: 'nearest' })
  }, [activeIndex, open])

  useEffect(() => {
    if (!open) return
    const closeOnOutsidePointer = (event: PointerEvent) => {
      if (!wrapperRef.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('pointerdown', closeOnOutsidePointer)
    return () => document.removeEventListener('pointerdown', closeOnOutsidePointer)
  }, [open])

  function openMenu(): void {
    if (unavailable) return
    setActiveIndex(Math.max(selectedIndex, 0))
    setOpen(true)
  }

  function selectOption(index: number): void {
    const option = options[index]
    if (option === undefined) return
    onChange(option.value)
    setOpen(false)
    requestAnimationFrame(() => triggerRef.current?.focus())
  }

  function moveSelection(offset: number): void {
    if (options.length === 0) return
    setActiveIndex((current) => (current + offset + options.length) % options.length)
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>): void {
    if (unavailable) return
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault()
      if (!open) {
        openMenu()
        return
      }
      moveSelection(event.key === 'ArrowDown' ? 1 : -1)
      return
    }
    if (event.key === 'Home' && open) {
      event.preventDefault()
      setActiveIndex(0)
      return
    }
    if (event.key === 'End' && open) {
      event.preventDefault()
      setActiveIndex(options.length - 1)
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault()
      if (open) selectOption(activeIndex)
      else openMenu()
      return
    }
    if (event.key === 'Escape' && open) {
      event.preventDefault()
      setOpen(false)
      triggerRef.current?.focus()
      return
    }
    if (event.key === 'Tab' && open) setOpen(false)
  }

  return (
    <div className="themed-select" ref={wrapperRef}>
      <button
        id={controlId}
        ref={triggerRef}
        type="button"
        className="themed-select-trigger"
        role="combobox"
        aria-label={label}
        aria-expanded={open}
        aria-controls={listboxId}
        aria-haspopup="listbox"
        aria-activedescendant={open ? `${listboxId}-option-${activeIndex}` : undefined}
        disabled={unavailable}
        onClick={() => open ? setOpen(false) : openMenu()}
        onKeyDown={onKeyDown}
      >
        <span>{selectedOption?.label ?? emptyLabel}</span>
        <ChevronDown aria-hidden="true" size={14} />
      </button>
      {open ? (
        <div id={listboxId} className="themed-select-menu" role="listbox" aria-label={label}>
          {options.map((option, index) => (
            <button
              id={`${listboxId}-option-${index}`}
              ref={(element) => { optionRefs.current[index] = element }}
              key={option.value}
              type="button"
              role="option"
              tabIndex={-1}
              aria-selected={option.value === value}
              data-active={index === activeIndex}
              className="themed-select-option"
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => selectOption(index)}
            >
              <span>{option.label}</span>
              {option.value === value ? <Check aria-hidden="true" size={13} /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
