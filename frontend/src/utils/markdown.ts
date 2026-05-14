import { marked } from 'marked'
import hljs from 'highlight.js'
import 'highlight.js/styles/atom-one-dark.css'

function escapeHtml(text: string): string {
  const map: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#039;' }
  return text.replace(/[&<>"']/g, m => map[m] || m)
}

const renderer = new marked.Renderer()
renderer.code = function(data: { text?: string; raw?: string; lang?: string } | string, ...args: unknown[]) {
  let code: string, lang: string
  if (typeof data === 'object' && data !== null) {
    code = data.text || data.raw || ''
    lang = data.lang || ''
  } else if (typeof data === 'string') {
    code = arguments[0] as string || ''
    lang = (arguments[1] as string) || ''
  } else {
    code = ''
    lang = ''
  }
  let highlighted: string
  try {
    if (lang && hljs.getLanguage(lang)) {
      highlighted = hljs.highlight(code, { language: lang }).value
    } else {
      highlighted = hljs.highlightAuto(code).value
    }
  } catch (e) {
    highlighted = escapeHtml(code)
  }
  return `<pre><code class="hljs language-${lang}">${highlighted}</code></pre>`
}

marked.setOptions({
  renderer: renderer,
  breaks: true,
  gfm: true
})

export function renderMarkdown(text: string): string {
  if (!text) return ''
  return marked.parse(text) as string
}

export { escapeHtml }
