module "stack" {
  source             = "../modules/stack"
  lambda_source_dir  = "${path.module}/../../lambda/logger"
  log_retention_days = 3
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "table" { value = module.stack.table }
