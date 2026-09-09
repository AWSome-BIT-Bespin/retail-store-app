{{- define "retail-redis.name" -}}
{{- default "retail-redis" .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "retail-redis.fullname" -}}
{{- default (include "retail-redis.name" .) .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "retail-redis.labels" -}}
app.kubernetes.io/name: {{ include "retail-redis.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: redis
app.kubernetes.io/owner: retail-store-sample
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version | replace "+" "_" }}
{{- end }}

{{- define "retail-redis.selectorLabels" -}}
app.kubernetes.io/name: {{ include "retail-redis.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: redis
app.kubernetes.io/owner: retail-store-sample
{{- end }}
