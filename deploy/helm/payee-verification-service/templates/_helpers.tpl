{{- define "payee.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{/*
Selector labels of the serving pod. component=service is the platform convention
(cicd-templates 335a345): every selector (Deployment, Service, PDB, topology spread,
NetworkPolicy) includes it, so none can select a pod of another component. Flyway runs
as the migrate init container of this pod, so there is no db-migration Job.
The Deployment selector is immutable: changing these labels on an installed release
needs the Deployment recreated (runbook section 2).
*/}}
{{- define "payee.selectorLabels" -}}
app.kubernetes.io/name: {{ include "payee.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
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
