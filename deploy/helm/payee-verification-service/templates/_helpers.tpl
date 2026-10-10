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

{{/*
Strict parse of a JDBC URL in config (review #13). Equivalent of the shared chart's
parse (cicd-templates 13ca2c6 charts/fintechbankx-service/templates/_helpers.tpl:76-211),
so the driver can only use the TLS settings checked here:
- jdbc:postgresql:// with exactly one '?' and no fragment; nothing that looks like a
  parameter ('=', '&', ';' or '%') before the '?';
- exactly one sslmode, equal to verify-full, and exactly one sslrootcert, equal to
  <databaseCaBundle.mountPath>/<databaseCaBundle.key>;
- no other ssl* key (sslfactory, sslfactoryarg, sslhostnameverifier,
  sslpasswordcallback, sslcert, ...) and no service: they can switch verification
  off or redirect the connection;
- TLS keys in lower case only; parameter names plain [A-Za-z0-9_.-] (not empty, so no
  percent-encoding either), and no %3D or %26 (any case) anywhere in the query, values
  included (13ca2c6 _helpers.tpl:175-177, 186-188).
Arguments: dict "key" (config key), "url", "root" (expected sslrootcert path).
*/}}
{{- define "payee.strictJdbcUrl" -}}
{{- $url := toString .url -}}
{{- $hint := printf "config.%s must be jdbc:postgresql://<host>:5432/<db>?sslmode=verify-full&sslrootcert=%s (deploy/terraform output jdbc_url)" .key .root -}}
{{- if not (hasPrefix "jdbc:postgresql://" $url) -}}
{{- fail (printf "%s: not a jdbc:postgresql:// URL" $hint) -}}
{{- end -}}
{{- if or (contains "#" $url) (ne 2 (len (splitList "?" $url))) -}}
{{- fail (printf "%s: it needs exactly one '?' and no fragment" $hint) -}}
{{- end -}}
{{- if regexMatch "[=&;%]" (index (splitList "?" $url) 0) -}}
{{- fail (printf "%s: no parameter may come before the '?'" $hint) -}}
{{- end -}}
{{- if regexMatch "(?i)%(3d|26)" (index (splitList "?" $url) 1) -}}
{{- fail (printf "%s: no percent-encoded '=' or '&' (%%3D, %%26) in the query" $hint) -}}
{{- end -}}
{{- $modes := 0 -}}
{{- $roots := 0 -}}
{{- range $pair := splitList "&" (index (splitList "?" $url) 1) -}}
{{- if not (contains "=" $pair) -}}
{{- fail (printf "%s: parameter %q has no value" $hint $pair) -}}
{{- end -}}
{{- $name := first (splitList "=" $pair) -}}
{{- $value := trimPrefix (printf "%s=" $name) $pair -}}
{{- if not (regexMatch "^[A-Za-z0-9_.-]+$" $name) -}}
{{- fail (printf "%s: parameter name %q is not plain [A-Za-z0-9_.-] (empty and percent-encoded names are refused)" $hint $name) -}}
{{- end -}}
{{- if or (hasPrefix "ssl" (lower $name)) (eq (lower $name) "service") -}}
{{- if ne $name (lower $name) -}}
{{- fail (printf "%s: TLS keys are lower case only (%s)" $hint $name) -}}
{{- else if eq $name "sslmode" -}}
{{- $modes = add1 $modes -}}
{{- if ne $value "verify-full" -}}
{{- fail (printf "%s: sslmode=%s is not verify-full" $hint $value) -}}
{{- end -}}
{{- else if eq $name "sslrootcert" -}}
{{- $roots = add1 $roots -}}
{{- if ne $value $.root -}}
{{- fail (printf "%s: sslrootcert=%s is not the mounted RDS CA bundle" $hint $value) -}}
{{- end -}}
{{- else -}}
{{- fail (printf "%s: %s is refused (it can switch certificate verification off or redirect the connection)" $hint $name) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if ne $modes 1 -}}
{{- fail (printf "%s: sslmode must appear exactly once (found %d)" $hint $modes) -}}
{{- end -}}
{{- if ne $roots 1 -}}
{{- fail (printf "%s: sslrootcert must appear exactly once (found %d)" $hint $roots) -}}
{{- end -}}
{{- end -}}

{{/*
config.* must not reach the datasource or Flyway around the parsed URL keys (review #13):
- no spring.datasource.*, spring.flyway.*, spring.liquibase.* or spring.r2dbc.* key in
  any spelling (SPRING_DATASOURCE_URL, spring.datasource.url, SPRING_R2DBC_URL,
  SPRING_DATASOURCE_HIKARI_DATA_SOURCE_PROPERTIES_*), except SPRING_DATASOURCE_USERNAME;
- no key whose name, with '.', '_' and '-' dropped, contains jdbcurl, sslfactory or
  sslhostnameverifier (13ca2c6 _helpers.tpl:105-111);
- no jdbc: URL in any value other than the parsed keys;
- no JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS or JAVA_OPTS that mention jdbc,
  ssl, spring (spring.config.*, spring.profiles.* included), kafka, flyway, liquibase,
  datasource, r2dbc or application.json, or that read more options from a file (an
  '@' argument file, -XX:VMOptionsFile, -XX:Flags): system properties outrank the
  environment (13ca2c6 _helpers.tpl:113-121; cicd-templates 2caa48f fbx.validateJvmOptions).
  config.* has no valueFrom, and extraEnv is refused whole (below).
Arguments: dict "config" (.Values.config), "urlKeys" (keys parsed by payee.strictJdbcUrl).
*/}}
{{- define "payee.refuseDatasourceOverrides" -}}
{{- $urlKeys := .urlKeys -}}
{{- range $key, $value := .config -}}
{{- $norm := regexReplaceAll "[._-]" (lower $key) "" -}}
{{- if and (regexMatch "^spring(datasource|flyway|liquibase|r2dbc)" $norm) (ne $norm "springdatasourceusername") -}}
{{- fail (printf "config.%s is refused: spring.datasource.*, spring.flyway.*, spring.liquibase.* and spring.r2dbc.* come from application.yml and the chart (the URL goes in config.DB_URL, which is parsed; only SPRING_DATASOURCE_USERNAME may be set)" $key) -}}
{{- end -}}
{{- if regexMatch "jdbcurl|sslfactory|sslhostnameverifier" $norm -}}
{{- fail (printf "config.%s is refused: a key naming a JDBC URL, an SSL factory or a host name verifier can override the datasource around %s, which the chart parses" $key (join ", " $urlKeys)) -}}
{{- end -}}
{{- if and (not (has $key $urlKeys)) (regexMatch "(?i)jdbc:" (toString $value)) -}}
{{- fail (printf "config.%s is refused: a jdbc: URL belongs only in %s, which the chart parses" $key (join ", " $urlKeys)) -}}
{{- end -}}
{{- if and (has $norm (list "javatooloptions" "jdkjavaoptions" "javaoptions" "javaopts")) (regexMatch "(?i)jdbc|ssl|spring|kafka|flyway|liquibase|datasource|r2dbc|application[._-]?json|(^|\\s)@|-XX:(VMOptionsFile|Flags)" (toString $value)) -}}
{{- fail (printf "config.%s is refused: JVM options must not mention jdbc, ssl, spring, kafka, flyway, liquibase, datasource, r2dbc or application.json, nor read options from a file ('@' argument file, -XX:VMOptionsFile, -XX:Flags) (system properties would override the datasource, the Kafka client or the profiles past the chart's checks)" $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
No configuration import or profile override from values (round 5):
- config.* must not set SPRING_CONFIG_IMPORT, SPRING_CONFIG_LOCATION,
  SPRING_CONFIG_ADDITIONAL_LOCATION (any spring.config.* spelling),
  SPRING_APPLICATION_JSON or SPRING_PROFILES_*: they can load another configuration,
  datasource included, or drop the profile that runs the startup TLS assertion.
  A configtree is allowed only as a chart-rendered value on
  optional:configtree:/etc/fintechbankx/config/; this chart renders none.
  Any suffix is refused too (SPRING_CONFIG_IMPORT_0, spring.config.import[0],
  SPRING_CONFIG_NAME): (?i)^spring[._-]?config[._-]?(import|location|
  additional[._-]?location|name), as cicd-templates 2caa48f fbx.datasourceOverrideName.
- config.* must not set spring.kafka.* or spring.ssl.* in any spelling
  ((?i)^spring[._-]?(kafka|ssl)[._-]): the Kafka security protocol, its trust store or
  host name check come from the kafka-* profile, and an SSL bundle could replace the
  trust anchors.
- config.* must not set the deployment marker FBX_DEPLOYED in any spelling (name
  normalising to FBXDEPLOYED): the chart renders FBX_DEPLOYED=true on every container
  that runs the startup TLS assertion, never from values.
- extraEnv: the chart renders none, so a value there would be silently ignored;
  it is refused, and settings go through config.* (checked above) instead.
Arguments: dict "config" (.Values.config), "extraEnv" (.Values.extraEnv).
*/}}
{{- define "payee.refuseConfigOverrides" -}}
{{- range $key, $value := .config -}}
{{- $norm := regexReplaceAll "[._-]" (lower $key) "" -}}
{{- if or (hasPrefix "springconfig" $norm) (eq $norm "springapplicationjson") (hasPrefix "springprofiles" $norm) -}}
{{- fail (printf "config.%s is refused: configuration imports and locations (SPRING_CONFIG_IMPORT, SPRING_CONFIG_LOCATION, SPRING_CONFIG_ADDITIONAL_LOCATION), SPRING_APPLICATION_JSON and the active profiles are chart-rendered only (a configtree only as optional:configtree:/etc/fintechbankx/config/)" $key) -}}
{{- end -}}
{{- if regexMatch "(?i)^spring[._-]?config[._-]?(import|location|additional[._-]?location|name)" $key -}}
{{- fail (printf "config.%s is refused: a config import, location or name can load a file or config tree that overrides the datasource past the sslmode=verify-full check; the chart renders no config import" $key) -}}
{{- end -}}
{{- if regexMatch "(?i)^spring[._-]?(kafka|ssl)[._-]" $key -}}
{{- fail (printf "config.%s is refused: spring.kafka.* and spring.ssl.* come from application.yml and the kafka-* profile (they set the Kafka security protocol and trust, and an SSL bundle could replace the trust anchors)" $key) -}}
{{- end -}}
{{- if eq (upper (regexReplaceAll "[^A-Za-z0-9]" (toString $key) "")) "FBXDEPLOYED" -}}
{{- fail (printf "config.%s is refused: FBX_DEPLOYED is the chart-owned deployment marker that enforces the startup TLS assertion; values never set it" $key) -}}
{{- end -}}
{{- end -}}
{{- with .extraEnv -}}
{{- $names := list -}}
{{- range $entry := . -}}
{{- $names = append $names (toString (get $entry "name")) -}}
{{- end -}}
{{- fail (printf "extraEnv is refused (%s): this chart renders no extraEnv; non-secret settings go in config.*, which is checked, and SPRING_CONFIG_IMPORT, SPRING_CONFIG_LOCATION and SPRING_CONFIG_ADDITIONAL_LOCATION are never accepted" (join ", " $names)) -}}
{{- end -}}
{{- end -}}
