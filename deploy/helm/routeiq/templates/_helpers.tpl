{{- define "routeiq.name" -}}{{ .Release.Name }}-routeiq{{- end -}}

{{- define "routeiq.labels" -}}
app.kubernetes.io/name: routeiq
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
