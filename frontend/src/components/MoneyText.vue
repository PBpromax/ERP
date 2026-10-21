<template>
  <span v-if="allowed">{{ formattedValue }}</span>
  <span v-else class="money-hidden">权限隐藏</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { canReadField } from '@/permission/field'
import { formatMoney, formatPrice } from '@/format/money'

const props = defineProps<{ value: any; field?: string }>()
const allowed = computed(() => canReadField(props.field || 'amount'))
const formattedValue = computed(() => /Price$/.test(props.field || '')
  ? formatPrice(props.value, '-') : formatMoney(props.value, '-'))
</script>
