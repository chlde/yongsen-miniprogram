<script setup lang="ts">
import { label } from '../services/format'
defineProps<{ value?: string }>()
</script>
<template><span class="badge" :class="value?.toLowerCase()">{{ label(value) }}</span></template>
