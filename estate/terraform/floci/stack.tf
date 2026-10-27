# The estate stack on Floci (ECS runs in mock mode there: cluster and task definitions are records).
module "stack" {
  source            = "../modules/stack"
  lambda_dir        = "${path.module}/../../lambda"

  aws_endpoint_url      = var.container_endpoint
  container_credentials = { AWS_ACCESS_KEY_ID = "test", AWS_SECRET_ACCESS_KEY = "test" }
  service_a_base_url    = var.service_a_url
  db_url                = var.db_url
  db_user               = "deliberator"
  db_password           = "deliberator"
  auto_publish          = var.auto_publish
  service_images        = { a = "estate/service-a:local", b = "estate/service-b:local" }

  # the chain: an ECS service with the CODE_DEPLOY controller (mock mode on Floci), Lambdas that reach the emulator
  ecs_cluster        = aws_ecs_cluster.estate.name
  lambda_environment = { AWS_ENDPOINT_URL = var.container_endpoint }
}

resource "aws_ecs_cluster" "estate" {
  name = "estate"
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "api_id" { value = module.stack.api_id }
output "chain_parameter" { value = module.stack.chain_parameter }
output "schedule_group" { value = module.stack.schedule_group }
output "ecs_service" { value = module.stack.ecs_service }
output "ecs_cluster" { value = aws_ecs_cluster.estate.name }
output "scheduler_create_function" { value = module.stack.scheduler_create_function }
output "ecs_scale_function" { value = module.stack.ecs_scale_function }
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
