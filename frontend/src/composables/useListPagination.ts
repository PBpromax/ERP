import { computed, ref, toValue, watch, type MaybeRefOrGetter, type WatchSource } from 'vue'
import { DEFAULT_PAGE_SIZE } from '../config/pagination.ts'

/** Paginate after filtering; totals and business calculations keep using the full source. */
export function useListPagination<T>(source: MaybeRefOrGetter<T[]>, resetOn: WatchSource[] = []) {
  const currentPage = ref(1)
  const total = computed(() => toValue(source).length)
  const pagedRows = computed(() => toValue(source).slice(
    (currentPage.value - 1) * DEFAULT_PAGE_SIZE, currentPage.value * DEFAULT_PAGE_SIZE))
  watch(total, count => { currentPage.value = Math.min(currentPage.value, Math.max(1, Math.ceil(count / DEFAULT_PAGE_SIZE))) })
  if (resetOn.length) watch(resetOn, () => { currentPage.value = 1 }, { deep: true })
  function rowIndex(index: number) { return (currentPage.value - 1) * DEFAULT_PAGE_SIZE + index + 1 }
  return { currentPage, pageSize: DEFAULT_PAGE_SIZE, total, pagedRows, rowIndex }
}
