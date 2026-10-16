# a sandbox AWS account stack on floci, plus an ECS cluster/task definition mock (floci runs ECS in mock mode).
module "stack" {
  source            = "../modules/stack"
  lambda_source_dir = "${path.module}/../../lambda"
}

resource "aws_ecs_cluster" "a sandbox AWS account" {
  name = "a sandbox AWS account"
}

resource "aws_ecs_task_definition" "service_a" {
  family                   = "a sandbox AWS account-service-a"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  container_definitions = jsonencode([{
    name         = "service-a"
    image        = "a sandbox AWS account/service-a:latest"
    essential    = true
    portMappings = [{ containerPort = 8081 }]
  }])
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "table" { value = module.stack.table }
