# The estate stack on Floci (ECS runs in mock mode there: cluster and task definitions are records).
module "stack" {
  source            = "../modules/stack"
  lambda_source_dir = "${path.module}/../../lambda/logger"

  aws_endpoint_url      = var.container_endpoint
  container_credentials = { AWS_ACCESS_KEY_ID = "test", AWS_SECRET_ACCESS_KEY = "test" }
  service_a_base_url    = var.service_a_url
  db_url                = var.db_url
  db_user               = "deliberator"
  db_password           = "deliberator"
  auto_publish          = var.auto_publish
  service_images        = { a = "estate/service-a:local", b = "estate/service-b:local" }
}

resource "aws_ecs_cluster" "estate" {
  name = "estate"
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "table" { value = module.stack.table }
output "task_definitions" {
  value     = module.stack.task_definitions
  sensitive = true
}

# local2: the same parameters, an api-key of its own
resource "aws_ssm_parameter" "local2" {
  provider = aws.local2
  for_each = module.stack.parameter_values
  name     = "/estate/${each.key}"
  type     = "String"
  value    = each.value
}

resource "aws_secretsmanager_secret" "local2_api_key" {
  provider                = aws.local2
  name                    = "estate/api-key"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "local2_api_key" {
  provider      = aws.local2
  secret_id     = aws_secretsmanager_secret.local2_api_key.id
  secret_string = "local2-${random_password.local2_api_key.result}"
}

resource "random_password" "local2_api_key" {
  length  = 24
  special = false
}
