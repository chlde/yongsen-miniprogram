export type JsonRecord = Record<string, any>
export type Role = 'ADMIN' | 'LEARNER'
export interface AuthSession { token: string; role: Role; identity?: string }
export interface Person extends JsonRecord { id: string; personNo: string; name: string; unit?: string; project?: string; status?: string; rowVersion?: number; bindingStatus?: string }
export interface Material extends JsonRecord { id: string; title: string; type: string; status: string; durationMs?: number; pageCount?: number; compatibilityCheckedAt?: string; previewUrl?: string; pageManifest?: JsonRecord[] }
export interface TrainingTask extends JsonRecord { id: string; title: string; status: string; description?: string; dueAt?: string; rowVersion?: number }
export interface Assignment extends JsonRecord { id: string; title?: string; taskTitle?: string; status: string; task?: TrainingTask; materials?: JsonRecord[]; taskMaterials?: JsonRecord[] }
export interface LearningEvent { eventId: string; sessionId: string; seq: number; type: string; elapsedMs: number; payload: JsonRecord }
