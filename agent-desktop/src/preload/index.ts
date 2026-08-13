import { contextBridge, ipcRenderer } from 'electron'

import { installDesktopBridge } from './bridge-factory.js'

installDesktopBridge(contextBridge, ipcRenderer)
