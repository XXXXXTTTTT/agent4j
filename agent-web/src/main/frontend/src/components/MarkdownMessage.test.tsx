import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { MarkdownMessage } from './MarkdownMessage'

describe('MarkdownMessage', () => {
  it('renders GFM structure instead of exposing markdown markers', () => {
    render(<MarkdownMessage markdown={'**重点**\n\n- 第一项\n- 第二项\n\n| 名称 | 状态 |\n| --- | --- |\n| Agent | 可用 |'} />)

    expect(screen.getByText('重点').tagName).toBe('STRONG')
    expect(screen.getByRole('list')).toBeInTheDocument()
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.queryByText('**重点**')).not.toBeInTheDocument()
  })

  it('renders fenced code with its declared language', () => {
    const { container } = render(
      <MarkdownMessage markdown={'```java\nSystem.out.println("ok");\n```'} />,
    )

    expect(container.querySelector('pre code.language-java')).toHaveTextContent('System.out.println("ok");')
  })

  it('renders safe images and rejects executable image protocols', () => {
    const { rerender } = render(
      <MarkdownMessage markdown={'![结果图](https://example.com/result.png)'} />,
    )
    expect(screen.getByRole('img', { name: '结果图' })).toHaveAttribute(
      'src',
      'https://example.com/result.png',
    )

    rerender(<MarkdownMessage markdown={'![危险图](javascript:alert(1))'} />)
    expect(screen.queryByRole('img', { name: '危险图' })).not.toBeInTheDocument()
  })

  it('does not execute raw html', () => {
    const { container } = render(
      <MarkdownMessage markdown={'<script>window.__unsafe = true</script><b>raw</b>'} />,
    )

    expect(container.querySelector('script')).not.toBeInTheDocument()
    expect(container.querySelector('b')).not.toBeInTheDocument()
    expect(container).toHaveTextContent('<script>window.__unsafe = true</script><b>raw</b>')
  })
})
