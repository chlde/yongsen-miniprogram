import { ref } from 'vue'

export function useFeedback() {
  const busy = ref(false)
  const error = ref('')
  const success = ref('')
  async function run<T>(action: () => Promise<T>, message = ''): Promise<T | undefined> {
    busy.value = true; error.value = ''; success.value = ''
    try { const result = await action(); success.value = message; return result }
    catch (e) { error.value = e instanceof Error ? e.message : String(e); return undefined }
    finally { busy.value = false }
  }
  return { busy, error, success, run }
}
