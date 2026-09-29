{{/*
차트 풀네임
*/}}
{{- define "auction-platform.fullname" -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
공통 라벨
*/}}
{{- define "auction-platform.labels" -}}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: auction-platform
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end }}

{{/*
서비스별 라벨 (name = 서비스 이름)
*/}}
{{- define "auction-platform.selectorLabels" -}}
app.kubernetes.io/name: {{ . }}
app.kubernetes.io/part-of: auction-platform
{{- end }}
