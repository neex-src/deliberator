# The estate stack, shared by terraform/floci and terraform/aws:
# inbox queue -> (service A) DynamoDB; service A -> EventBridge bus -> rule -> {B's queue, Lambda}.
terraform {
  required_providers {
    aws     = { source = "hashicorp/aws", version = "~> 5.0" }
    archive = { source = "hashicorp/archive", version = "~> 2.4" }
    random  = { source = "hashicorp/random", version = "~> 3.6" }
  }
}

variable "prefix" {
  type    = string
  default = "estate"
}
variable "lambda_source_dir" { type = string }
variable "service_a_base_url" {
  type        = string
  default     = "http://localhost:8081"
  description = "How service B reaches service A (a container name on the local runtime network)."
}
variable "aws_endpoint_url" {
  type        = string
  default     = ""
  description = "Set on the local emulator only: the endpoint the services' SDKs use, as seen from their container."
}
variable "container_credentials" {
  type        = map(string)
  default     = {}
  description = "Fake AWS_* credentials for the local emulator; empty on real AWS (the task role provides them)."
}
variable "service_images" {
  type = map(string)
  default = {
    a = "estate/service-a:latest"
    b = "estate/service-b:latest"
  }
}
variable "auto_publish" {
  type        = string
  default     = "false"
  description = "APP_AUTO_PUBLISH of service A: publish to the bus as soon as an inbox message is stored."
}
variable "db_url" {
  type    = string
  default = "jdbc:postgresql://localhost:5432/estate"
}
variable "db_user" {
  type    = string
  default = "estate"
}
variable "db_password" {
  type        = string
  default     = null
  sensitive   = true
  description = "null: a random password (real AWS). The local runtime's Postgres gets a fixed fake one."
}
variable "region" {
  type    = string
  default = "eu-central-1"
}
variable "log_retention_days" {
  type    = number
  default = 3
}

locals {
  inbox_queue  = "${var.prefix}-inbox"
  events_queue = "${var.prefix}-b-events"
  table        = "${var.prefix}-messages"
  bus          = "${var.prefix}-bus"
  secret       = "${var.prefix}/api-key"
  lambda_name  = "${var.prefix}-logger"
}

resource "aws_sqs_queue" "inbox" {
  name                       = local.inbox_queue
  max_message_size           = 262144
  visibility_timeout_seconds = 60
}

resource "aws_sqs_queue" "events" {
  name                       = local.events_queue
  max_message_size           = 262144
  visibility_timeout_seconds = 60
}

resource "aws_dynamodb_table" "messages" {
  name         = local.table
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "id"
  attribute {
    name = "id"
    type = "S"
  }
}

resource "aws_cloudwatch_event_bus" "bus" {
  name = local.bus
}

resource "aws_cloudwatch_event_rule" "published" {
  name           = "${var.prefix}-published"
  event_bus_name = aws_cloudwatch_event_bus.bus.name
  event_pattern = jsonencode({
    source        = ["estate.service-a"]
    "detail-type" = ["payload.published"]
  })
}

resource "aws_cloudwatch_event_target" "to_b" {
  rule           = aws_cloudwatch_event_rule.published.name
  event_bus_name = aws_cloudwatch_event_bus.bus.name
  target_id      = "service-b-queue"
  arn            = aws_sqs_queue.events.arn
}

resource "aws_cloudwatch_event_target" "to_lambda" {
  rule           = aws_cloudwatch_event_rule.published.name
  event_bus_name = aws_cloudwatch_event_bus.bus.name
  target_id      = "logger-lambda"
  arn            = aws_lambda_function.logger.arn
}

# EventBridge may only write to B's queue on behalf of this rule
resource "aws_sqs_queue_policy" "events_from_rule" {
  queue_url = aws_sqs_queue.events.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.events.arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.published.arn } }
    }]
  })
}

# --- lambda ---
data "archive_file" "lambda" {
  type        = "zip"
  source_dir  = var.lambda_source_dir
  output_path = "${path.module}/.build/logger.zip"
}

resource "aws_iam_role" "lambda" {
  name = "${var.prefix}-logger"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "lambda_logs" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_cloudwatch_log_group" "lambda" {
  name              = "/aws/lambda/${local.lambda_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_lambda_function" "logger" {
  function_name    = local.lambda_name
  role             = aws_iam_role.lambda.arn
  runtime          = "nodejs20.x"
  handler          = "index.handler"
  filename         = data.archive_file.lambda.output_path
  source_code_hash = data.archive_file.lambda.output_base64sha256
  timeout          = 10
  depends_on       = [aws_cloudwatch_log_group.lambda]
}

resource "aws_lambda_permission" "from_events" {
  statement_id  = "AllowEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.logger.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.published.arn
}

# --- logs for the two services ---
resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["service-a", "service-b"])
  name              = "/${var.prefix}/${each.key}"
  retention_in_days = var.log_retention_days
}

# --- configuration the services receive (ECS `secrets` point at these names) ---
locals {
  parameters = {
    "common/table-name"            = aws_dynamodb_table.messages.name
    "common/bus-name"              = aws_cloudwatch_event_bus.bus.name
    "service-a/inbox-queue"        = aws_sqs_queue.inbox.name
    "service-b/events-queue"       = aws_sqs_queue.events.name
    "service-b/service-a-base-url" = var.service_a_base_url
    "service-b/db-url"             = var.db_url
    "service-b/db-user"            = var.db_user
  }
}

resource "aws_ssm_parameter" "p" {
  for_each = local.parameters
  name     = "/${var.prefix}/${each.key}"
  type     = "String"
  value    = each.value
}

# shared secret: B sends it to A as X-Api-Key, A checks it
resource "random_password" "api_key" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "api_key" {
  name                    = local.secret
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "api_key" {
  secret_id     = aws_secretsmanager_secret.api_key.id
  secret_string = random_password.api_key.result
}

resource "random_password" "db" {
  length  = 24
  special = false
}

resource "aws_secretsmanager_secret" "db_password" {
  name                    = "${var.prefix}/db-password"
  recovery_window_in_days = 0
}

resource "aws_secretsmanager_secret_version" "db_password" {
  secret_id     = aws_secretsmanager_secret.db_password.id
  secret_string = coalesce(var.db_password, random_password.db.result)
}

# --- ECS task definitions: the config chain's middle link ---
resource "aws_iam_role" "task_execution" {
  name = "${var.prefix}-task-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "task_execution_config" {
  name = "read-config"
  role = aws_iam_role.task_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ssm:GetParameters"], Resource = [for p in aws_ssm_parameter.p : p.arn] },
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [aws_secretsmanager_secret.api_key.arn, aws_secretsmanager_secret.db_password.arn] },
    ]
  })
}

locals {
  ssm_arn = { for k, p in aws_ssm_parameter.p : k => p.arn }
  common_environment = merge(
    { AWS_REGION = var.region },
    var.aws_endpoint_url == "" ? {} : { AWS_ENDPOINT_URL = var.aws_endpoint_url },
    var.container_credentials,
  )
  services = {
    a = {
      port        = 8081
      environment = merge(local.common_environment, { APP_AUTO_PUBLISH = var.auto_publish })
      secrets = {
        TABLE_NAME  = local.ssm_arn["common/table-name"]
        BUS_NAME    = local.ssm_arn["common/bus-name"]
        INBOX_QUEUE = local.ssm_arn["service-a/inbox-queue"]
        API_KEY     = aws_secretsmanager_secret.api_key.arn
      }
    }
    b = {
      port        = 8082
      environment = local.common_environment
      secrets = {
        EVENTS_QUEUE  = local.ssm_arn["service-b/events-queue"]
        SERVICE_A_URL = local.ssm_arn["service-b/service-a-base-url"]
        API_KEY       = aws_secretsmanager_secret.api_key.arn
        DB_URL        = local.ssm_arn["service-b/db-url"]
        DB_USER       = local.ssm_arn["service-b/db-user"]
        DB_PASSWORD   = aws_secretsmanager_secret.db_password.arn
      }
    }
  }
}

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.services
  family                   = "${var.prefix}-service-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 256
  memory                   = 512
  execution_role_arn       = aws_iam_role.task_execution.arn
  container_definitions = templatefile("${path.module}/taskdef.json.tftpl", {
    name        = "service-${each.key}"
    image       = var.service_images[each.key]
    port        = each.value.port
    environment = each.value.environment
    secrets     = each.value.secrets
    log_group   = aws_cloudwatch_log_group.service["service-${each.key}"].name
    region      = var.region
  })
}

output "inbox_queue" { value = aws_sqs_queue.inbox.name }
output "events_queue" { value = aws_sqs_queue.events.name }
output "table" { value = aws_dynamodb_table.messages.name }
output "bus" { value = aws_cloudwatch_event_bus.bus.name }
output "lambda" { value = aws_lambda_function.logger.function_name }
# service key (a, b) -> the rendered ECS container definitions (JSON text); `bb estate:deploy` reads this
output "task_definitions" {
  value     = { for k, t in aws_ecs_task_definition.service : k => t.container_definitions }
  sensitive = true
}
