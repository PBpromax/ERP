import { onBeforeUnmount, onMounted, ref, toValue, type MaybeRefOrGetter } from 'vue'

const DEFAULT_INTERVAL_MS = 60_000

/** Refresh a visible list without interrupting an open form or detail view. */
export function usePageAutoRefresh(
  refresh: () => void | Promise<void>,
  paused: MaybeRefOrGetter<boolean> = false,
  intervalMs = DEFAULT_INTERVAL_MS
) {
  const refreshing = ref(false)
  let timer: ReturnType<typeof window.setInterval> | undefined
  let lastRefreshAt = Date.now()

  async function runRefresh() {
    if (refreshing.value || document.visibilityState !== 'visible' || toValue(paused)) return
    refreshing.value = true
    try {
      await refresh()
      lastRefreshAt = Date.now()
    } finally {
      refreshing.value = false
    }
  }

  function refreshOnReturn() {
    if (document.visibilityState === 'visible' && Date.now() - lastRefreshAt >= intervalMs) void runRefresh()
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', refreshOnReturn)
    timer = window.setInterval(() => { void runRefresh() }, intervalMs)
  })

  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', refreshOnReturn)
    if (timer) window.clearInterval(timer)
  })

  return { refreshing, refreshNow: runRefresh }
}
