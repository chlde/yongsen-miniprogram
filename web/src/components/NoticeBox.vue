<script setup lang="ts">defineProps<{ text?: string; kind?: 'error' | 'success' | 'info' }>()</script>
<template><div v-if="text" class="notice" :class="kind || 'info'" :role="kind === 'error' ? 'alert' : 'status'" data-testid="notice">{{ text }}</div></template>
