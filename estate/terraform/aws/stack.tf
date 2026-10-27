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

variable "run_services" {
  type        = bool
  default     = false
  description = "Off (default): the task definitions point at images that do not exist and nothing runs. On: services A and B really run on Fargate (run.tf): ECR repositories (images pushed by `bb estate:real-images`), a task role, the awslogs driver, public IPs in the default VPC's subnets (no NAT), Cloud Map for B -> A, Postgres as a sidecar of B's task. Implies with_ecs. The services start with desired count 0; push the images, then `aws ecs update-service --desired-count 1`."
}

variable "allowed_cidrs" {
  type        = list(string)
  default     = []
  description = "CIDR blocks that may reach the services' ports 8081 and 8082 from outside the VPC (run_services): your own address as a /32. Empty: only the VPC."
}

locals {
  ecs = var.with_ecs || var.run_services
}

data "aws_vpc" "default" {
  count   = local.ecs ? 1 : 0
  default = true
}

data "aws_subnets" "default" {
  count = local.ecs ? 1 : 0
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default[0].id]
  }
}

resource "aws_ecs_cluster" "estate" {
  count = local.ecs ? 1 : 0
  name  = var.prefix
}

module "stack" {
  source             = "../modules/stack"
  prefix             = var.prefix
  lambda_dir         = "${path.module}/../../lambda"
  log_retention_days = 3

  ecs_cluster               = local.ecs ? aws_ecs_cluster.estate[0].name : ""
  ecs_deployment_controller = "ECS"
  ecs_subnets               = local.ecs ? slice(sort(data.aws_subnets.default[0].ids), 0, 1) : []
  ecs_desired_count         = 0

  # the real running estate (run.tf); every line below is the module's default when run_services is off
  real_tasks               = var.run_services
  task_cpu                 = var.run_services ? 512 : 256
  task_memory              = var.run_services ? 1024 : 512
  service_images           = var.run_services ? { for k, r in aws_ecr_repository.service : k => "${r.repository_url}:latest" } : { a = "estate/service-a:latest", b = "estate/service-b:latest" }
  service_a_base_url       = var.run_services ? "http://service-a.${var.prefix}.local:8081" : "http://localhost:8081"
  ecs_assign_public_ip     = var.run_services
  ecs_security_groups      = var.run_services ? [aws_security_group.services[0].id] : []
  ecs_service_registry_arn = var.run_services ? aws_service_discovery_service.a[0].arn : ""
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "table" { value = module.stack.table }
output "api_endpoint" { value = module.stack.api_endpoint }
output "chain_parameter" { value = module.stack.chain_parameter }
output "schedule_group" { value = module.stack.schedule_group }
output "scheduler_create_function" { value = module.stack.scheduler_create_function }
output "ecs_scale_function" { value = module.stack.ecs_scale_function }
output "ecs_service" { value = module.stack.ecs_service }
output "ecs_cluster" { value = local.ecs ? aws_ecs_cluster.estate[0].name : "" }
output "ecs_service_b" { value = module.stack.ecs_service_b }
output "ecr_repositories" { value = { for k, r in aws_ecr_repository.service : k => r.repository_url } }

# Looked up by name in the IAM root (../iam), which must be applied first in the account: the edge `terraform.graph` reads.
data "aws_iam_role" "shared" {
  name = "estate-shared"
}

output "shared_role_arn" { value = data.aws_iam_role.shared.arn }
