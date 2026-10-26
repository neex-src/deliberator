variable "prefix" {
  type        = string
  default     = "estate"
  description = "Name prefix of every resource (the sandbox runs use m4chain)."
}

variable "with_ecs" {
  type        = bool
  default     = false
  description = "Also create an ECS cluster and a service with the plain ECS controller and desired count 0 (no cost) so that ecs-scale really calls UpdateService. The CODE_DEPLOY service is never created here: it needs an ALB and target groups."
}

data "aws_vpc" "default" {
  count   = var.with_ecs ? 1 : 0
  default = true
}

data "aws_subnets" "default" {
  count = var.with_ecs ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default[0].id]
  }
}

resource "aws_ecs_cluster" "estate" {
  count = var.with_ecs ? 1 : 0
  name  = var.prefix
}

module "stack" {
  source             = "../modules/stack"
  prefix             = var.prefix
  lambda_dir         = "${path.module}/../../lambda"
  log_retention_days = 3

  ecs_cluster               = var.with_ecs ? aws_ecs_cluster.estate[0].name : ""
  ecs_deployment_controller = "ECS"
  ecs_subnets               = var.with_ecs ? slice(sort(data.aws_subnets.default[0].ids), 0, 1) : []
  ecs_desired_count         = 0
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "table" { value = module.stack.table }
output "api_endpoint" { value = module.stack.api_endpoint }
output "chain_parameter" { value = module.stack.chain_parameter }
output "schedule_group" { value = module.stack.schedule_group }
output "scheduler_create_function" { value = module.stack.scheduler_create_function }
output "ecs_scale_function" { value = module.stack.ecs_scale_function }
output "ecs_service" { value = module.stack.ecs_service }

# Looked up by name in the IAM root (../iam), which must be applied first in the account: the edge `terraform.graph` reads.
data "aws_iam_role" "shared" {
  name = "estate-shared"
}

output "shared_role_arn" { value = data.aws_iam_role.shared.arn }
