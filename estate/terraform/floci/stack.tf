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
