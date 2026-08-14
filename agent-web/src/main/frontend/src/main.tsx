import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { App } from './App'
import { AppearanceProvider } from './appearance/AppearanceProvider'
import './styles.css'

const root = document.getElementById('root')
if (root === null) throw new Error('找不到工作台根节点')

createRoot(root).render(
  <StrictMode>
    <AppearanceProvider><App /></AppearanceProvider>
  </StrictMode>,
)
