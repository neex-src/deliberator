# a sandbox AWS account stack on the real account: SQS, DynamoDB on-demand, EventBridge, Lambda, SSM, one secret, short-retention
# log groups, IAM roles. No NAT, no ALB, no VPC, no ECS. Cost ~0 at sandbox volumes. Never applied from this
# machine until deploy keys exist: `terraform validate` only.
module "stack" {
  source             = "../modules/stack"
  lambda_source_dir  = "${path.module}/../../lambda"
  log_retention_days = 3
}

output "inbox_queue" { value = module.stack.inbox_queue }
output "table" { value = module.stack.table }
