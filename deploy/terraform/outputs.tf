output "workload_role_arn" {
  description = "IRSA role for the Helm value serviceAccount.roleArn."
  value       = aws_iam_role.workload.arn
}

output "jdbc_url" {
  description = "Helm value config.DB_URL (TLS verified against the platform RDS CA bundle the chart mounts)."
  value       = "jdbc:postgresql://${aws_rds_cluster.database.endpoint}:5432/${local.database}?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem"
}

output "reader_endpoint" {
  description = "Aurora reader endpoint for audit and reconciliation queries."
  value       = aws_rds_cluster.database.reader_endpoint
}

output "app_db_secret_name" {
  description = "Helm value externalSecret.remoteSecretName (runtime role)."
  value       = aws_secretsmanager_secret.app_database.name
}

output "migration_db_secret_name" {
  description = "Helm value externalSecret.migrationRemoteSecretName (schema owner, Flyway only)."
  value       = aws_secretsmanager_secret.migration_database.name
}

output "import_db_secret_name" {
  description = "Credential of the operator running db/import/import-payee-directory.sh."
  value       = aws_secretsmanager_secret.import_database.name
}

output "ops_db_secret_name" {
  description = "Secrets Manager name the DBA fills for payee_verification_ops (db/ops/park-outbox-event.sh)."
  value       = aws_secretsmanager_secret.ops_database.name
}

output "master_user_secret_arn" {
  description = "RDS-managed admin credential, for the DBA bootstrap only."
  value       = aws_rds_cluster.database.master_user_secret[0].secret_arn
}

output "kafka_topic_namespace" {
  description = "Topics the IRSA role may produce to."
  value       = "evt.of.payee.*"
}

output "log_group_name" {
  value = module.service_base.cloudwatch_log_group_name
}
