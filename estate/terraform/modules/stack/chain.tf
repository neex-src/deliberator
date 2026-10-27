# The chain (brief §6), on top of the stack in main.tf:
#   HTTP API (API Gateway v2)  POST /schedule -> scheduler-create (Lambda) -> one-off EventBridge Scheduler schedule
#                              ANY  /log      -> logger (Lambda)
#   schedule fires -> ecs-scale (Lambda) -> ECS UpdateService (desired count) + SSM PutParameter (the config update)
# The ECS service uses the CODE_DEPLOY deployment controller (M5 deploys to it). It needs a cluster, so it exists only when
# `ecs_cluster` is set; real AWS leaves it out (CODE_DEPLOY needs a load balancer and target groups that cost money per hour).

variable "ecs_cluster" {
  type        = string
  default     = ""
  description = "Cluster of the ECS service the chain scales. Empty: no service, ecs-scale reports `scaled: false` and still puts the parameter."
}
variable "ecs_deployment_controller" {
  type        = string
  default     = "CODE_DEPLOY"
  description = "CODE_DEPLOY | ECS | EXTERNAL."
}
variable "ecs_subnets" {
  type    = list(string)
  default = ["subnet-00000000"]
}
variable "ecs_desired_count" {
  type    = number
  default = 1
}
variable "ecs_assign_public_ip" {
  type        = bool
  default     = false
  description = "Real tasks in the default VPC's public subnets pull their images and reach AWS through a public IP (no NAT gateway)."
}
variable "ecs_security_groups" {
  type    = list(string)
  default = []
}
variable "ecs_service_registry_arn" {
  type        = string
  default     = ""
  description = "A Cloud Map service of service A (B reaches A as service-a.<namespace>)."
}
variable "ecs_b_desired_count" {
  type    = number
  default = 0
}
variable "ecs_target_group_arn" {
  type        = string
  default     = ""
  description = "Set when a load balancer exists (a real CODE_DEPLOY service needs one)."
}
variable "lambda_environment" {
  type        = map(string)
  default     = {}
  description = "Extra environment of the chain's Lambdas: on the local emulator AWS_ENDPOINT_URL, nothing on real AWS."
}

locals {
  chain_service   = "${var.prefix}-service-a"
  chain_parameter = "/${var.prefix}/chain/desired-count"
  chain_group     = "${var.prefix}-chain"
  lambda_assume = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

# --- the ECS service (CODE_DEPLOY) ---
resource "aws_ecs_service" "chain" {
  count           = var.ecs_cluster == "" ? 0 : 1
  name            = local.chain_service
  cluster         = var.ecs_cluster
  task_definition = aws_ecs_task_definition.service["a"].arn
  desired_count   = var.ecs_desired_count
  launch_type     = "FARGATE"

  deployment_controller {
    type = var.ecs_deployment_controller
  }
  network_configuration {
    subnets          = var.ecs_subnets
    security_groups  = var.ecs_security_groups
    assign_public_ip = var.ecs_assign_public_ip
  }
  dynamic "service_registries" {
    for_each = var.ecs_service_registry_arn == "" ? [] : [1]
    content {
      registry_arn = var.ecs_service_registry_arn
    }
  }
  dynamic "load_balancer" {
    for_each = var.ecs_target_group_arn == "" ? [] : [1]
    content {
      target_group_arn = var.ecs_target_group_arn
      container_name   = "service-a"
      container_port   = 8081
    }
  }
  # CodeDeploy owns the task definition and the load balancer after the first deployment; the chain owns the desired count
  lifecycle {
    ignore_changes = [task_definition, desired_count, load_balancer]
  }
}

# --- service B as a real ECS service (real_tasks only): plain ECS controller, public IP, the Postgres sidecar in its task ---
resource "aws_ecs_service" "b" {
  count           = var.ecs_cluster != "" && var.real_tasks ? 1 : 0
  name            = "${var.prefix}-service-b"
  cluster         = var.ecs_cluster
  task_definition = aws_ecs_task_definition.service["b"].arn
  desired_count   = var.ecs_b_desired_count
  launch_type     = "FARGATE"
  network_configuration {
    subnets          = var.ecs_subnets
    security_groups  = var.ecs_security_groups
    assign_public_ip = var.ecs_assign_public_ip
  }
  # deploys and the desired count are driven from outside Terraform (Deliberator's deployed checks register revisions and call UpdateService)
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
}

# --- the config value the chain updates: Terraform seeds it, ecs-scale owns it afterwards ---
resource "aws_ssm_parameter" "chain_desired" {
  name  = local.chain_parameter
  type  = "String"
  value = "1"
  lifecycle {
    ignore_changes = [value]
  }
}

# --- ecs-scale ---
data "archive_file" "ecs_scale" {
  type        = "zip"
  source_dir  = "${var.lambda_dir}/ecs-scale"
  output_path = "${path.module}/.build/ecs-scale.zip"
}

resource "aws_iam_role" "ecs_scale" {
  name               = "${var.prefix}-ecs-scale"
  assume_role_policy = local.lambda_assume
}

resource "aws_iam_role_policy_attachment" "ecs_scale_logs" {
  role       = aws_iam_role.ecs_scale.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "ecs_scale" {
  name = "scale-and-put"
  role = aws_iam_role.ecs_scale.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecs:UpdateService"], Resource = "*" },
      { Effect = "Allow", Action = ["ssm:PutParameter"], Resource = [aws_ssm_parameter.chain_desired.arn] },
    ]
  })
}

resource "aws_cloudwatch_log_group" "ecs_scale" {
  name              = "/aws/lambda/${var.prefix}-ecs-scale"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "ecs_scale" {
  function_name    = "${var.prefix}-ecs-scale"
  role             = aws_iam_role.ecs_scale.arn
  runtime          = "nodejs20.x"
  handler          = "index.handler"
  filename         = data.archive_file.ecs_scale.output_path
  source_code_hash = data.archive_file.ecs_scale.output_base64sha256
  timeout          = 30
  environment {
    variables = merge({ AWS_NODEJS_CONNECTION_REUSE_ENABLED = "1" }, var.lambda_environment)
  }
  depends_on = [aws_cloudwatch_log_group.ecs_scale]
}

# --- the schedule group and the role the scheduler assumes to invoke ecs-scale ---
resource "aws_scheduler_schedule_group" "chain" {
  name = local.chain_group
}

resource "aws_iam_role" "scheduler" {
  name = "${var.prefix}-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "scheduler.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy" "scheduler_invoke" {
  name = "invoke-ecs-scale"
  role = aws_iam_role.scheduler.id
  policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = ["lambda:InvokeFunction"], Resource = [aws_lambda_function.ecs_scale.arn] }]
  })
}

# --- scheduler-create ---
data "archive_file" "scheduler_create" {
  type        = "zip"
  source_dir  = "${var.lambda_dir}/scheduler-create"
  output_path = "${path.module}/.build/scheduler-create.zip"
}

resource "aws_iam_role" "scheduler_create" {
  name               = "${var.prefix}-scheduler-create"
  assume_role_policy = local.lambda_assume
}

resource "aws_iam_role_policy_attachment" "scheduler_create_logs" {
  role       = aws_iam_role.scheduler_create.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy" "scheduler_create" {
  name = "create-schedules"
  role = aws_iam_role.scheduler_create.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["scheduler:CreateSchedule", "scheduler:GetSchedule", "scheduler:DeleteSchedule"], Resource = "*" },
      { Effect = "Allow", Action = ["iam:PassRole"], Resource = [aws_iam_role.scheduler.arn] },
    ]
  })
}

resource "aws_cloudwatch_log_group" "scheduler_create" {
  name              = "/aws/lambda/${var.prefix}-scheduler-create"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "scheduler_create" {
  function_name    = "${var.prefix}-scheduler-create"
  role             = aws_iam_role.scheduler_create.arn
  runtime          = "nodejs20.x"
  handler          = "index.handler"
  filename         = data.archive_file.scheduler_create.output_path
  source_code_hash = data.archive_file.scheduler_create.output_base64sha256
  timeout          = 10
  environment {
    variables = merge({
      SCHEDULE_GROUP     = aws_scheduler_schedule_group.chain.name
      SCALE_FUNCTION_ARN = aws_lambda_function.ecs_scale.arn
      SCHEDULER_ROLE_ARN = aws_iam_role.scheduler.arn
      ECS_CLUSTER        = var.ecs_cluster
      ECS_SERVICE        = var.ecs_cluster == "" ? "" : local.chain_service
      PARAMETER_NAME     = aws_ssm_parameter.chain_desired.name
    }, var.lambda_environment)
  }
  depends_on = [aws_cloudwatch_log_group.scheduler_create]
}

# --- the HTTP API in front of logger and scheduler-create ---
resource "aws_apigatewayv2_api" "http" {
  name          = "${var.prefix}-http"
  protocol_type = "HTTP"
}

resource "aws_apigatewayv2_integration" "logger" {
  api_id                 = aws_apigatewayv2_api.http.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.logger.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_integration" "scheduler_create" {
  api_id                 = aws_apigatewayv2_api.http.id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.scheduler_create.invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "log" {
  api_id    = aws_apigatewayv2_api.http.id
  route_key = "ANY /log"
  target    = "integrations/${aws_apigatewayv2_integration.logger.id}"
}

resource "aws_apigatewayv2_route" "schedule" {
  api_id    = aws_apigatewayv2_api.http.id
  route_key = "POST /schedule"
  target    = "integrations/${aws_apigatewayv2_integration.scheduler_create.id}"
}

resource "aws_apigatewayv2_stage" "default" {
  api_id      = aws_apigatewayv2_api.http.id
  name        = "$default"
  auto_deploy = true
}

resource "aws_lambda_permission" "from_api_logger" {
  statement_id  = "AllowHttpApi"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.logger.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
}

resource "aws_lambda_permission" "from_api_schedule" {
  statement_id  = "AllowHttpApi"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.scheduler_create.function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.http.execution_arn}/*/*"
}

output "api_id" { value = aws_apigatewayv2_api.http.id }
output "api_endpoint" { value = aws_apigatewayv2_api.http.api_endpoint }
output "chain_parameter" { value = aws_ssm_parameter.chain_desired.name }
output "schedule_group" { value = aws_scheduler_schedule_group.chain.name }
output "scheduler_create_function" { value = aws_lambda_function.scheduler_create.function_name }
output "ecs_scale_function" { value = aws_lambda_function.ecs_scale.function_name }
output "ecs_service" { value = var.ecs_cluster == "" ? "" : local.chain_service }
output "ecs_service_b" { value = var.ecs_cluster != "" && var.real_tasks ? "${var.prefix}-service-b" : "" }
