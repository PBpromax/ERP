import { defineStore } from 'pinia'
import http from '@/api/http'
import { invalidateReferenceData } from '@/api/referenceData'
import { canAccessPath, firstAccessiblePath, type MenuEntry, type UserProfile } from '@/permission/menus'

interface ProfileResponse { user: UserProfile; menuCatalog: MenuEntry[]; fieldPermissions: Record<string, boolean>; token?: string }
let pendingProfile: Promise<void> | null = null
let pendingToken = ''
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('OMS_TOKEN') || '',
    user: null as UserProfile | null,
    menuCatalog: [] as MenuEntry[],
    fieldPermissions: {} as Record<string, boolean>
  }),
  getters: {
    isLoggedIn: state => !!state.token,
    menuCodes: state => state.user?.effectiveMenuCodes || [],
    canReadOrderPrices: state => state.user?.orderPricesVisible === true,
    canReadMoney: state => state.fieldPermissions['*'] === true || state.fieldPermissions['money.read'] === true,
    firstPath(): string { return firstAccessiblePath(this.menuCodes, this.menuCatalog) }
  },
  actions: {
    canAccess(path: string) { return canAccessPath(path, this.menuCodes, this.menuCatalog) },
    hasMenu(code: string) { return this.menuCodes.includes(code) },
    applyProfile(data: ProfileResponse) {
      const previous = JSON.stringify(this.menuCodes)
      this.user = data.user
      this.menuCatalog = data.menuCatalog
      this.fieldPermissions = data.fieldPermissions || {}
      if (previous !== JSON.stringify(this.menuCodes)) invalidateReferenceData()
    },
    async login(username: string, password: string) {
      this.clearSession()
      const data = await http.post<ProfileResponse, ProfileResponse>('/auth/login', { username, password })
      this.token = data.token!
      localStorage.setItem('OMS_TOKEN', this.token)
      this.applyProfile(data)
    },
    async loadProfile() {
      if (!this.token) return
      if (pendingProfile && pendingToken === this.token) return pendingProfile
      const requestedToken = this.token
      pendingToken = requestedToken
      const request = http.get<ProfileResponse, ProfileResponse>('/auth/me').then(data => {
        if (this.token === requestedToken) this.applyProfile(data)
      }).catch(error => {
        if (error?.response?.status === 401 && this.token === requestedToken) this.clearSession()
        throw error
      }).finally(() => {
        if (pendingToken === requestedToken) pendingProfile = null
      })
      pendingProfile = request
      return request
    },
    clearSession() {
      this.token = ''; this.user = null; this.menuCatalog = []; this.fieldPermissions = {}
      localStorage.removeItem('OMS_TOKEN')
      invalidateReferenceData()
    },
    async logout() {
      try { if (this.token) await http.post('/auth/logout', {}, { skipErrorToast: true }) }
      finally { this.clearSession() }
    }
  }
})
