# a sandbox AWS account stack, shared by terraform/floci and terraform/aws:
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
  default = "a sandbox AWS account"
}
variable "lambda_source_dir" { type = string }
variable "service_a_base_url" {
  type    = string
  default = "http://localhost:8081"
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
    source        = ["a sandbox AWS account.service-a"]
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

# --- configuration the services read at startup ---
resource "aws_ssm_parameter" "p" {
  for_each = {
    "common/table-name"            = aws_dynamodb_table.messages.name
    "common/bus-name"              = aws_cloudwatch_event_bus.bus.name
    "service-a/inbox-queue"        = aws_sqs_queue.inbox.name
    "service-b/events-queue"       = aws_sqs_queue.events.name
    "service-b/service-a-base-url" = var.service_a_base_url
  }
  name  = "/neex/${var.prefix}/${each.key}"
  type  = "String"
  value = each.value
}

# the one secret: B sends it to A as X-Api-Key, A checks it
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

output "inbox_queue" { value = aws_sqs_queue.inbox.name }
output "events_queue" { value = aws_sqs_queue.events.name }
output "table" { value = aws_dynamodb_table.messages.name }
output "bus" { value = aws_cloudwatch_event_bus.bus.name }
output "lambda" { value = aws_lambda_function.logger.function_name }
