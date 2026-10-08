{{- define "payee.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "payee.selectorLabels" -}}
app.kubernetes.io/name: {{ include "payee.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "payee.labels" -}}
{{ include "payee.selectorLabels" . }}
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: fintechbankx-open-finance
app.kubernetes.io/managed-by: {{ .Release.Service }}
fintechbankx.io/service-id: {{ .Values.serviceId }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "payee.namespace" -}}
{{- .Values.namespace | default .Release.Namespace -}}
{{- end -}}

{{- define "payee.secretName" -}}
{{ include "payee.name" . }}-db
{{- end -}}

{{- define "payee.migrationSecretName" -}}
{{ include "payee.name" . }}-db-migration
{{- end -}}

{{/* Container hardening shared by the migration init container and the service. */}}
{{- define "payee.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop: ["ALL"]
{{- end -}}
