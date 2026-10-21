import test from 'node:test'
import assert from 'node:assert/strict'
import { computed, effectScope, nextTick, ref } from 'vue'
import { useListPagination } from '../src/composables/useListPagination.ts'

test('100-row pages preserve all records, indexes and totals across the boundary', () => {
  const source = ref(Array.from({ length: 201 }, (_, id) => ({ id, amount: 10 })))
  const scope = effectScope()
  scope.run(() => {
    const page = useListPagination(source)
    assert.equal(page.pageSize, 100)
    assert.equal(page.pagedRows.value.length, 100)
    assert.equal(page.total.value, 201)
    page.currentPage.value = 2
    assert.equal(page.rowIndex(0), 101)
    assert.equal(page.pagedRows.value[0].id, 100)
    page.currentPage.value = 3
    assert.equal(page.pagedRows.value.length, 1)
    assert.equal(page.rowIndex(0), 201)
    assert.equal(source.value.reduce((sum, row) => sum + row.amount, 0), 2010)
  })
  scope.stop()
})

test('search filters the entire source and resets a later page', async () => {
  const source = ref(Array.from({ length: 201 }, (_, id) => ({ name: `record ${id}` })))
  const keyword = ref('')
  const filtered = computed(() => source.value.filter(row => row.name.includes(keyword.value)))
  const scope = effectScope()
  const page = scope.run(() => useListPagination(filtered, [keyword]))
  page.currentPage.value = 2
  keyword.value = 'record 200'
  await nextTick()
  assert.equal(page.currentPage.value, 1)
  assert.equal(page.pagedRows.value[0].name, 'record 200')
  assert.equal(page.total.value, 1)
  scope.stop()
})

test('refreshing or removing records clamps the page and keeps empty lists usable', async () => {
  const source = ref(Array.from({ length: 101 }, (_, id) => id))
  const scope = effectScope()
  const page = scope.run(() => useListPagination(source))
  page.currentPage.value = 2
  source.value = source.value.slice(0, 100)
  await nextTick()
  assert.equal(page.currentPage.value, 1)
  assert.equal(page.pagedRows.value.length, 100)
  source.value = []
  await nextTick()
  assert.equal(page.currentPage.value, 1)
  assert.equal(page.total.value, 0)
  assert.deepEqual(page.pagedRows.value, [])
  scope.stop()
})
