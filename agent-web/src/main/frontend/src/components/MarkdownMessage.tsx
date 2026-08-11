import type { ComponentPropsWithoutRef } from 'react'
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown'
import remarkGfm from 'remark-gfm'

interface MarkdownMessageProps {
  markdown: string
  className?: string
}

const SAFE_DATA_IMAGE = /^data:image\/(?:png|jpeg|webp|gif);base64,[a-z0-9+/=\r\n]+$/i

function safeUrlTransform(url: string, key: string): string {
  if (key === 'src' && SAFE_DATA_IMAGE.test(url)) return url
  return defaultUrlTransform(url)
}

function SafeLink({ href, ...props }: ComponentPropsWithoutRef<'a'>) {
  return <a {...props} href={href} rel="noreferrer noopener" target="_blank" />
}

function SafeImage({ alt, src, ...props }: ComponentPropsWithoutRef<'img'>) {
  if (src === undefined || src.length === 0) return <span>{alt ?? '图片'}</span>
  return <img {...props} alt={alt ?? ''} loading="lazy" src={src} />
}

/** 将模型 Markdown 安全渲染为可扩展富内容，默认不解析原始 HTML。 */
export function MarkdownMessage({ markdown, className }: MarkdownMessageProps) {
  return (
    <div className={className === undefined ? 'markdown-message' : `markdown-message ${className}`}>
      <ReactMarkdown
        components={{ a: SafeLink, img: SafeImage }}
        remarkPlugins={[remarkGfm]}
        urlTransform={safeUrlTransform}
      >
        {markdown}
      </ReactMarkdown>
    </div>
  )
}
